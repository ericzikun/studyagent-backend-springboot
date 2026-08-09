package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.Gson;
import com.stripe.Stripe;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Charge;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionSchedule;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.SubscriptionScheduleCreateParams;
import com.stripe.param.SubscriptionScheduleReleaseParams;
import com.stripe.param.SubscriptionScheduleUpdateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.studyagent.service.domain.billing.BasicTrialAccount;
import com.studyagent.service.domain.billing.IntroTrialPlans;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.billing.BillingAddon;
import com.studyagent.service.domain.billing.BillingAccessState;
import com.studyagent.service.domain.billing.BillingCatalogResult;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.BillingHostedInvoiceResult;
import com.studyagent.service.domain.billing.BillingEntitlementPolicy;
import com.studyagent.service.domain.billing.BillingPlan;
import com.studyagent.service.domain.billing.BillingPortalSessionResult;
import com.studyagent.service.domain.billing.BillingRecordPageResult;
import com.studyagent.service.domain.billing.BillingRecordResult;
import com.studyagent.service.domain.billing.SubscriptionResult;
import com.studyagent.service.domain.payment.CheckoutSessionResult;
import com.studyagent.service.domain.quota.PlanQuotaService;
import com.studyagent.service.domain.quota.QuotaVipAccessService;
import com.studyagent.service.domain.user.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingDomainServiceImpl implements BillingDomainService {
    private record UpgradeCreditBasis(int netPaidCents, String sourceInvoiceId) {
    }
    private static final Set<String> BLOCKING_SUBSCRIPTION_STATUSES = Set.of(
            "active", "trialing", "past_due", "unpaid", "incomplete", "paused"
    );
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_BILLING_RECORD_LIMIT = 50;
    private static final int MAX_BILLING_RECORD_LIMIT = 50;
    private static final DateTimeFormatter BILLING_RECORD_CURSOR_FORMATTER =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String BILLING_RECORD_EFFECTIVE_TIME_SQL =
            "COALESCE(paid_at, created_at, updated_at)";

    enum PlanChangeAction {
        NOOP,
        IMMEDIATE_UPGRADE,
        DEFERRED_CHANGE,
        UNSUPPORTED
    }

    private record BillingRecordCursor(LocalDateTime effectiveAt, Long id) {
    }

    private final SubscriptionPlanMapper subscriptionPlanMapper;
    private final AddonPackageDefMapper addonPackageDefMapper;
    private final UserSubscriptionMapper userSubscriptionMapper;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final PlanQuotaService planQuotaService;
    private final UserRepository userRepository;
    private final QuotaVipAccessService quotaVipAccessService;
    private final UserSubscriptionBootstrapService userSubscriptionBootstrapService;

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    @Value("${payment.success-url:http://localhost:3000/payment-success}")
    private String successUrl;

    @Value("${payment.cancel-url:http://localhost:3000/payment-canceled}")
    private String cancelUrl;

    @Value("${billing.portal.mock-url:}")
    private String billingPortalMockUrl;

    @Value("${billing.checkout.mock-enabled:false}")
    private boolean billingCheckoutMockEnabled;

    @Value("${billing.intro-trial.enabled:true}")
    private boolean introTrialEnabled;

    @Value("${billing.intro-trial.plan-code:" + IntroTrialPlans.TRIAL_PLAN_CODE + "}")
    private String introTrialPlanCode;

    @Value("${billing.intro-trial.conversion-plan-code:" + IntroTrialPlans.CONVERSION_PLAN_CODE + "}")
    private String introTrialConversionPlanCode;

    @Value("${billing.intro-trial.allow-direct-purchase-basic:false}")
    private boolean allowDirectPurchaseBasic;

    @PostConstruct
    public void initializeStripe() {
        if (stripeSecretKey != null && !stripeSecretKey.isBlank()) {
            Stripe.apiKey = stripeSecretKey;
        }
    }

    @Override
    public BillingCatalogResult getCatalog() {
        List<BillingPlan> plans = subscriptionPlanMapper.selectList(
                        new LambdaQueryWrapper<SubscriptionPlanEntity>()
                                .eq(SubscriptionPlanEntity::getIsActive, true)
                                .orderByAsc(SubscriptionPlanEntity::getDisplayOrder))
                .stream()
                .filter(plan -> !isIntroTrialPlan(plan)
                        || IntroTrialPlans.isSellableIntroTrialPlanCode(plan.getPlanCode()))
                .map(this::toPlan)
                .toList();
        List<BillingAddon> addons = addonPackageDefMapper.selectList(
                        new LambdaQueryWrapper<AddonPackageDefEntity>()
                                .eq(AddonPackageDefEntity::getIsActive, true)
                                .orderByAsc(AddonPackageDefEntity::getDisplayOrder))
                .stream()
                .map(this::toAddon)
                .toList();
        return BillingCatalogResult.builder().plans(plans).addons(addons).build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CheckoutSessionResult createSubscriptionCheckout(
            String clerkUserId,
            String customerEmail,
            String planCode,
            String requestedSuccessUrl,
            String requestedCancelUrl,
            String resumeToken) {
        userSubscriptionBootstrapService.ensureExists(clerkUserId);
        return createSubscriptionCheckoutLocked(
                clerkUserId,
                customerEmail,
                planCode,
                requestedSuccessUrl,
                requestedCancelUrl,
                resumeToken);
    }

    private CheckoutSessionResult createSubscriptionCheckoutLocked(
            String clerkUserId,
            String customerEmail,
            String planCode,
            String requestedSuccessUrl,
            String requestedCancelUrl,
            String resumeToken) {
        boolean mockCheckout = shouldUseMockCheckout();
        if (!mockCheckout) {
            requireStripeConfigured();
        }
        SubscriptionPlanEntity plan = requirePlan(planCode, !mockCheckout);
        assertSellableIntroTrialPlan(plan);
        UserSubscriptionEntity userSubscription = getOrCreateUserSubscription(clerkUserId);
        assertCurrentSubscriptionCannotBuyIntroTrial(userSubscription, plan);
        if (mockCheckout) {
            return createMockSubscriptionCheckout(
                    clerkUserId,
                    plan,
                    userSubscription,
                    requestedSuccessUrl,
                    resumeToken);
        }
        UserSubscriptionEntity lockedSubscription = userSubscriptionMapper.selectByUserForUpdate(clerkUserId);
        if (lockedSubscription != null) {
            userSubscription = lockedSubscription;
        }
        if (BillingEntitlementPolicy.requiresPaymentResolution(userSubscription.getStatus())
                && isRealStripeReference(userSubscription.getStripeSubscriptionId())) {
            throw new BillingDomainException(
                    "PAYMENT_RESOLUTION_REQUIRED",
                    "Resolve the existing subscription payment before changing plans");
        }
        if (BLOCKING_SUBSCRIPTION_STATUSES.contains(userSubscription.getStatus())
                && isRealStripeReference(userSubscription.getStripeSubscriptionId())) {
            rejectUnsupportedPaidPlanTarget(userSubscription, plan);
            return createManualUpgradeCheckout(
                    clerkUserId,
                    customerEmail,
                    plan,
                    userSubscription,
                    requestedSuccessUrl,
                    requestedCancelUrl,
                    resumeToken);
        }

        assertLapsedCheckoutAllowed(userSubscription, plan);

        boolean introTrialCheckout = introTrialEnabled && isIntroTrialPlan(plan);
        String conversionPlanCode = introTrialCheckout ? resolveConversionPlanCode(plan) : null;
        String orderType = introTrialCheckout
                ? IntroTrialPlans.ORDER_TYPE_INTRO_TRIAL
                : "subscription_initial";
        String purchaseType = introTrialCheckout
                ? IntroTrialPlans.PURCHASE_TYPE_INTRO_TRIAL
                : "subscription";

        CheckoutSessionResult reusableCheckout = findReusableInitialCheckout(
                clerkUserId, planCode, plan, resumeToken, orderType);
        if (reusableCheckout != null) {
            return reusableCheckout;
        }

        String customerId = ensureStripeCustomer(userSubscription, clerkUserId, customerEmail);
        if (introTrialCheckout) {
            assertIntroTrialEligible(userSubscription, customerId);
        }
        String finalSuccessUrl = resolveCheckoutSuccessUrl(requestedSuccessUrl, resumeToken);
        String finalCancelUrl = resolveCheckoutCancelUrl(requestedCancelUrl);
        SessionCreateParams.SubscriptionData.Builder subscriptionDataBuilder =
                SessionCreateParams.SubscriptionData.builder()
                        .putMetadata("purchase_type", purchaseType)
                        .putMetadata("clerk_user_id", clerkUserId)
                        .putMetadata("plan_code", planCode);
        if (introTrialCheckout) {
            subscriptionDataBuilder
                    .putMetadata("conversion_plan_code", conversionPlanCode)
                    .putMetadata("change_type", IntroTrialPlans.SCHEDULE_CHANGE_TYPE_INTRO_CONVERSION);
        }
        SessionCreateParams.SubscriptionData subscriptionData = subscriptionDataBuilder.build();
        SessionCreateParams params = buildSubscriptionCheckoutParams(
                clerkUserId,
                customerId,
                planCode,
                plan,
                finalSuccessUrl,
                finalCancelUrl,
                subscriptionData,
                purchaseType,
                conversionPlanCode);

        try {
            return createInitialSubscriptionCheckout(
                    clerkUserId,
                    planCode,
                    plan,
                    resumeToken,
                    params,
                    orderType);
        } catch (StripeException e) {
            if (shouldRetrySubscriptionCheckoutWithFreshCustomer(userSubscription, e)) {
                UserSubscriptionEntity retrySubscription = userSubscription;
                if (!clearStoredStripeCustomer(userSubscription)) {
                    retrySubscription = getOrCreateUserSubscription(clerkUserId);
                }
                String retriedCustomerId = ensureStripeCustomer(retrySubscription, clerkUserId, customerEmail);
                if (introTrialCheckout) {
                    assertIntroTrialEligible(retrySubscription, retriedCustomerId);
                }
                SessionCreateParams retriedParams = buildSubscriptionCheckoutParams(
                        clerkUserId,
                        retriedCustomerId,
                        planCode,
                        plan,
                        finalSuccessUrl,
                        finalCancelUrl,
                        subscriptionData,
                        purchaseType,
                        conversionPlanCode);
                try {
                    return createInitialSubscriptionCheckout(
                            clerkUserId,
                            planCode,
                            plan,
                            resumeToken,
                            retriedParams,
                            orderType);
                } catch (StripeException retryException) {
                    throw stripeFailure("Create subscription Checkout failed", retryException);
                }
            }
            throw stripeFailure("Create subscription Checkout failed", e);
        }
    }

    private boolean shouldUseMockCheckout() {
        return billingCheckoutMockEnabled && !isStripeConfigured();
    }

    private SessionCreateParams buildSubscriptionCheckoutParams(
            String clerkUserId,
            String customerId,
            String planCode,
            SubscriptionPlanEntity plan,
            String finalSuccessUrl,
            String finalCancelUrl,
            SessionCreateParams.SubscriptionData subscriptionData,
            String purchaseType,
            String conversionPlanCode) {
        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .setClientReferenceId(clerkUserId)
                .setSuccessUrl(finalSuccessUrl)
                .setCancelUrl(finalCancelUrl)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(plan.getStripePriceId())
                        .setQuantity(1L)
                        .build())
                .putMetadata("purchase_type", purchaseType)
                .putMetadata("clerk_user_id", clerkUserId)
                .putMetadata("plan_code", planCode)
                .setSubscriptionData(subscriptionData);
        if (IntroTrialPlans.PURCHASE_TYPE_INTRO_TRIAL.equals(purchaseType)) {
            builder.putMetadata("conversion_plan_code", conversionPlanCode)
                    .putMetadata("change_type", IntroTrialPlans.SCHEDULE_CHANGE_TYPE_INTRO_CONVERSION);
        }
        return builder.build();
    }

    private CheckoutSessionResult createInitialSubscriptionCheckout(
            String clerkUserId,
            String planCode,
            SubscriptionPlanEntity plan,
            String resumeToken,
            SessionCreateParams params,
            String orderType) throws StripeException {
        Session session = createStripeCheckoutSession(params);
        try {
            insertPendingOrder(
                    clerkUserId,
                    orderType,
                    "subscription",
                    planCode,
                    planCode,
                    null,
                    0L,
                    null,
                    plan.getPriceCents(),
                    plan.getCurrency(),
                    session.getId(),
                    null,
                    session.getSubscription());
        } catch (RuntimeException e) {
            expireCheckoutAfterPersistenceFailure(session, e);
            throw e;
        }
        return CheckoutSessionResult.builder()
                .checkoutKind("session")
                .sessionId(session.getId())
                .referenceId(session.getId())
                .checkoutUrl(session.getUrl())
                .expiresAt(session.getExpiresAt())
                .resumeToken(resumeToken)
                .quotedAmountCents(plan.getPriceCents())
                .build();
    }

    private CheckoutSessionResult findReusableInitialCheckout(
            String clerkUserId,
            String planCode,
            SubscriptionPlanEntity plan,
            String resumeToken,
            String orderType) {
        RechargeOrderEntity pending = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                        .eq(RechargeOrderEntity::getOrderType, orderType)
                        .in(RechargeOrderEntity::getStatus,
                                List.of("pending", "pending_checkout", "checkout_created"))
                        .orderByDesc(RechargeOrderEntity::getCreatedAt)
                        .last("LIMIT 1 FOR UPDATE"));
        if (pending == null || !hasText(pending.getStripeSessionId())) {
            return null;
        }
        try {
            Session session = retrieveStripeCheckoutSession(pending.getStripeSessionId());
            long nowEpoch = Instant.now().getEpochSecond();
            boolean notExpired = session.getExpiresAt() == null || session.getExpiresAt() > nowEpoch;
            boolean samePlan = planCode.equals(pending.getPlanCode());
            if (samePlan
                    && "open".equals(session.getStatus())
                    && notExpired
                    && hasText(session.getUrl())) {
                return CheckoutSessionResult.builder()
                        .checkoutKind("session")
                        .sessionId(session.getId())
                        .referenceId(session.getId())
                        .checkoutUrl(session.getUrl())
                        .expiresAt(session.getExpiresAt())
                        .resumeToken(resumeToken)
                        .quotedAmountCents(plan.getPriceCents())
                        .build();
            }
            if ("complete".equals(session.getStatus()) || "paid".equals(session.getPaymentStatus())) {
                throw new BillingDomainException(
                        "SUBSCRIPTION_CHANGE_PENDING",
                        "The existing subscription payment is still being applied");
            }
            if ("open".equals(session.getStatus())) {
                expireStripeCheckoutSession(session);
            }
            markCheckoutOrderExpired(pending.getId(), "stripe_session_not_reusable");
            return null;
        } catch (StripeException e) {
            throw stripeFailure("Inspect existing subscription Checkout failed", e);
        }
    }

    private void markCheckoutOrderExpired(Long orderId, String reason) {
        rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getId, orderId)
                .set(RechargeOrderEntity::getStatus, "checkout_expired")
                .set(RechargeOrderEntity::getFailureReason, reason)
                .set(RechargeOrderEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private CheckoutSessionResult createMockSubscriptionCheckout(
            String clerkUserId,
            SubscriptionPlanEntity plan,
            UserSubscriptionEntity current,
            String requestedSuccessUrl,
            String resumeToken) {
        LocalDateTime now = LocalDateTime.now();
        String sessionId = "mock_cs_" + UUID.randomUUID().toString().replace("-", "");
        String customerId = hasText(current.getStripeCustomerId())
                ? current.getStripeCustomerId()
                : "mock_cus_" + UUID.randomUUID().toString().replace("-", "");
        String subscriptionId = hasText(current.getStripeSubscriptionId())
                ? current.getStripeSubscriptionId()
                : "mock_sub_" + UUID.randomUUID().toString().replace("-", "");
        boolean introTrial = isIntroTrialPlan(plan);
        String conversionPlanCode = introTrial ? resolveConversionPlanCode(plan) : null;
        LocalDateTime currentPeriodEnd = introTrial
                ? now.plusDays(IntroTrialPlans.resolveTrialDays(plan.getTrialDays()))
                : resolvePeriodEnd(now, plan.getBillingInterval());
        LocalDateTime quotaPeriodEnd = introTrial
                ? currentPeriodEnd
                : ("year".equals(plan.getBillingInterval()) ? now.plusMonths(1) : currentPeriodEnd);
        LocalDateTime introTrialUsedAt = introTrial
                ? (current.getIntroTrialUsedAt() == null ? now : current.getIntroTrialUsedAt())
                : current.getIntroTrialUsedAt();
        String subscriptionPhase = introTrial
                ? IntroTrialPlans.PHASE_INTRO
                : IntroTrialPlans.PHASE_STANDARD;

        userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, current.getId())
                .set(UserSubscriptionEntity::getTier, plan.getTier())
                .set(UserSubscriptionEntity::getPlanCode, plan.getPlanCode())
                .set(UserSubscriptionEntity::getStatus, "active")
                .set(UserSubscriptionEntity::getStripeCustomerId, customerId)
                .set(UserSubscriptionEntity::getStripeSubscriptionId, subscriptionId)
                .set(UserSubscriptionEntity::getStripeScheduleId, null)
                .set(UserSubscriptionEntity::getCurrentPeriodStart, now)
                .set(UserSubscriptionEntity::getCurrentPeriodEnd, currentPeriodEnd)
                .set(UserSubscriptionEntity::getQuotaPeriodStart, now)
                .set(UserSubscriptionEntity::getQuotaPeriodEnd, quotaPeriodEnd)
                .set(UserSubscriptionEntity::getCancelAtPeriodEnd, false)
                .set(UserSubscriptionEntity::getPendingPlanCode, conversionPlanCode)
                .set(UserSubscriptionEntity::getPendingEffectiveAt, introTrial ? currentPeriodEnd : null)
                .set(UserSubscriptionEntity::getPendingUpgradeOrderNo, null)
                .set(UserSubscriptionEntity::getPendingUpgradeExpiresAt, null)
                .set(UserSubscriptionEntity::getIntroTrialUsedAt, introTrialUsedAt)
                .set(UserSubscriptionEntity::getSubscriptionPhase, subscriptionPhase)
                .set(UserSubscriptionEntity::getLastSyncedAt, now)
                .set(UserSubscriptionEntity::getUpdatedAt, now));
        current.setTier(plan.getTier());
        current.setPlanCode(plan.getPlanCode());
        current.setStatus("active");
        current.setStripeCustomerId(customerId);
        current.setStripeSubscriptionId(subscriptionId);
        current.setStripeScheduleId(null);
        current.setCurrentPeriodStart(now);
        current.setCurrentPeriodEnd(currentPeriodEnd);
        current.setQuotaPeriodStart(now);
        current.setQuotaPeriodEnd(quotaPeriodEnd);
        current.setCancelAtPeriodEnd(false);
        current.setPendingPlanCode(conversionPlanCode);
        current.setPendingEffectiveAt(introTrial ? currentPeriodEnd : null);
        current.setPendingUpgradeOrderNo(null);
        current.setPendingUpgradeExpiresAt(null);
        current.setIntroTrialUsedAt(introTrialUsedAt);
        current.setSubscriptionPhase(subscriptionPhase);
        current.setLastSyncedAt(now);
        current.setUpdatedAt(now);

        insertCompletedMockSubscriptionOrder(clerkUserId, plan, sessionId, subscriptionId, now);
        planQuotaService.resetFromPaidInvoice(
                clerkUserId,
                subscriptionId,
                plan.getPlanCode(),
                now.toInstant(ZoneOffset.UTC),
                quotaPeriodEnd.toInstant(ZoneOffset.UTC),
                sessionId,
                introTrial ? IntroTrialPlans.ORDER_TYPE_INTRO_TRIAL : "subscription_initial");

        String checkoutUrl = resolveMockCheckoutSuccessUrl(
                resolveCheckoutSuccessUrl(requestedSuccessUrl, resumeToken),
                sessionId);
        return CheckoutSessionResult.builder()
                .checkoutKind("session")
                .sessionId(sessionId)
                .referenceId(sessionId)
                .checkoutUrl(checkoutUrl)
                .expiresAt(now.plusMinutes(30).toEpochSecond(ZoneOffset.UTC))
                .resumeToken(resumeToken)
                .quotedAmountCents(plan.getPriceCents())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CheckoutSessionResult createAddonCheckout(
            String clerkUserId,
            String customerEmail,
            String addonCode,
            String requestedSuccessUrl,
            String requestedCancelUrl,
            String resumeToken) {
        requireStripeConfigured();
        if (!isPaidMember(clerkUserId)) {
            throw new BillingDomainException("ADDON_REQUIRES_PAID_MEMBER", "A paid subscription is required");
        }
        AddonPackageDefEntity addon = requireAddon(addonCode);
        UserSubscriptionEntity userSubscription = getOrCreateUserSubscription(clerkUserId);
        String customerId = ensureStripeCustomer(userSubscription, clerkUserId, customerEmail);

        Session session;
        try {
            session = createStripeCheckoutSession(buildAddonCheckoutParams(
                    clerkUserId,
                    addonCode,
                    requestedSuccessUrl,
                    requestedCancelUrl,
                    resumeToken,
                    addon,
                    customerId));
        } catch (StripeException e) {
            if (!isMissingStripeCustomer(e) || !clearStoredStripeCustomer(userSubscription)) {
                throw stripeFailure("Create add-on Checkout failed", e);
            }
            UserSubscriptionEntity retrySubscription = getOrCreateUserSubscription(clerkUserId);
            String retriedCustomerId = ensureStripeCustomer(retrySubscription, clerkUserId, customerEmail);
            try {
                session = createStripeCheckoutSession(buildAddonCheckoutParams(
                        clerkUserId,
                        addonCode,
                        requestedSuccessUrl,
                        requestedCancelUrl,
                        resumeToken,
                        addon,
                        retriedCustomerId));
                userSubscription = retrySubscription;
            } catch (StripeException retryException) {
                throw stripeFailure("Create add-on Checkout failed", retryException);
            }
        }
        try {
            insertPendingOrder(
                    clerkUserId,
                    "addon",
                    addon.getFeatureCode(),
                    addonCode,
                    null,
                    addonCode,
                    addon.getQuotaAmount(),
                    addon.getValidityMonths(),
                    addon.getPriceCents(),
                    addon.getCurrency(),
                    session.getId(),
                    session.getPaymentIntent(),
                    userSubscription.getStripeSubscriptionId());
        } catch (RuntimeException e) {
            expireCheckoutAfterPersistenceFailure(session, e);
            throw e;
        }
        return CheckoutSessionResult.builder()
                .checkoutKind("session")
                .sessionId(session.getId())
                .referenceId(session.getId())
                .checkoutUrl(session.getUrl())
                .expiresAt(session.getExpiresAt())
                .resumeToken(resumeToken)
                .build();
    }

    private SessionCreateParams buildAddonCheckoutParams(
            String clerkUserId,
            String addonCode,
            String requestedSuccessUrl,
            String requestedCancelUrl,
            String resumeToken,
            AddonPackageDefEntity addon,
            String customerId) {
        SessionCreateParams.PaymentIntentData paymentIntentData = SessionCreateParams.PaymentIntentData.builder()
                .putMetadata("purchase_type", "addon")
                .putMetadata("clerk_user_id", clerkUserId)
                .putMetadata("addon_code", addonCode)
                .build();
        return SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCustomer(customerId)
                .setClientReferenceId(clerkUserId)
                .setSuccessUrl(resolveCheckoutSuccessUrl(requestedSuccessUrl, resumeToken))
                .setCancelUrl(resolveCheckoutCancelUrl(requestedCancelUrl))
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(addon.getStripePriceId())
                        .setQuantity(1L)
                        .build())
                .putMetadata("purchase_type", "addon")
                .putMetadata("clerk_user_id", clerkUserId)
                .putMetadata("addon_code", addonCode)
                .setPaymentIntentData(paymentIntentData)
                .setInvoiceCreation(SessionCreateParams.InvoiceCreation.builder()
                        .setEnabled(true)
                        .setInvoiceData(SessionCreateParams.InvoiceCreation.InvoiceData.builder()
                                .setDescription("Add-on purchase: " + addonCode)
                                .putMetadata("purchase_type", "addon")
                                .putMetadata("clerk_user_id", clerkUserId)
                                .putMetadata("addon_code", addonCode)
                                .build())
                        .build())
                .build();
    }

    @Override
    public BillingPortalSessionResult createBillingPortalSession(String clerkUserId, String requestedReturnUrl) {
        UserSubscriptionEntity current = findByUser(clerkUserId);
        if (current == null || !hasText(current.getStripeCustomerId())) {
            throw new BillingDomainException(
                    "STRIPE_CUSTOMER_NOT_FOUND",
                    "No Stripe billing customer found for user");
        }

        String returnUrl = resolveBillingPortalReturnUrl(requestedReturnUrl);
        if (!isStripeConfigured() && hasText(billingPortalMockUrl)) {
            return BillingPortalSessionResult.builder()
                    .url(resolveMockBillingPortalUrl(returnUrl, current.getStripeCustomerId()))
                    .build();
        }
        requireStripeConfigured();
        com.stripe.param.billingportal.SessionCreateParams params =
                com.stripe.param.billingportal.SessionCreateParams.builder()
                        .setCustomer(current.getStripeCustomerId())
                        .setReturnUrl(returnUrl)
                        .build();
        try {
            com.stripe.model.billingportal.Session session = createStripeBillingPortalSession(params);
            return BillingPortalSessionResult.builder()
                    .url(session.getUrl())
                    .build();
        } catch (StripeException e) {
            throw stripeFailure("Create billing portal session failed", e);
        }
    }

    @Override
    public BillingRecordPageResult getBillingRecords(String clerkUserId, String cursor, Integer limit) {
        int pageSize = resolveBillingRecordLimit(limit);
        BillingRecordCursor decodedCursor = decodeBillingRecordCursor(cursor);
        LambdaQueryWrapper<RechargeOrderEntity> query = new LambdaQueryWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                .gt(RechargeOrderEntity::getPriceCents, 0);
        applyBillingRecordCursor(query, decodedCursor);

        List<RechargeOrderEntity> orders = rechargeOrderMapper.selectList(query
                .last("ORDER BY "
                        + BILLING_RECORD_EFFECTIVE_TIME_SQL
                        + " DESC, id DESC LIMIT "
                        + (pageSize + 1)));
        boolean hasMore = orders.size() > pageSize;
        List<RechargeOrderEntity> pageOrders = hasMore ? orders.subList(0, pageSize) : orders;
        String nextCursor = hasMore && !pageOrders.isEmpty()
                ? encodeBillingRecordCursor(pageOrders.get(pageOrders.size() - 1))
                : null;

        return BillingRecordPageResult.builder()
                .items(pageOrders.stream().map(this::toBillingRecord).toList())
                .nextCursor(nextCursor)
                .build();
    }

    private int resolveBillingRecordLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_BILLING_RECORD_LIMIT;
        }
        return Math.min(limit, MAX_BILLING_RECORD_LIMIT);
    }

    private BillingRecordCursor decodeBillingRecordCursor(String cursor) {
        if (!hasText(cursor)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2 || !hasText(parts[0]) || !hasText(parts[1])) {
                throw new IllegalArgumentException("Invalid billing records cursor");
            }
            LocalDateTime effectiveAt = LocalDateTime.parse(parts[0], BILLING_RECORD_CURSOR_FORMATTER);
            Long id = Long.valueOf(parts[1]);
            if (id <= 0) {
                throw new IllegalArgumentException("Invalid billing records cursor id");
            }
            return new BillingRecordCursor(effectiveAt, id);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new BillingDomainException(
                    "INVALID_BILLING_RECORD_CURSOR",
                    "Invalid billing records cursor");
        }
    }

    private void applyBillingRecordCursor(
            LambdaQueryWrapper<RechargeOrderEntity> query,
            BillingRecordCursor cursor) {
        if (cursor == null) {
            return;
        }
        // Cursor predicates mirror the list ordering:
        // effective billing time DESC, id DESC. Effective time is the same
        // fallback timestamp returned to the frontend as paidAt.
        query.apply(
                "("
                        + BILLING_RECORD_EFFECTIVE_TIME_SQL
                        + " < {0} OR ("
                        + BILLING_RECORD_EFFECTIVE_TIME_SQL
                        + " = {0} AND id < {1}))",
                cursor.effectiveAt(),
                cursor.id());
    }

    private String encodeBillingRecordCursor(RechargeOrderEntity order) {
        LocalDateTime effectiveAt = firstNonNull(order.getPaidAt(), order.getCreatedAt(), order.getUpdatedAt());
        if (order.getId() == null || effectiveAt == null) {
            return null;
        }
        String payload = String.join("|",
                BILLING_RECORD_CURSOR_FORMATTER.format(effectiveAt),
                String.valueOf(order.getId()));
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public BillingHostedInvoiceResult createBillingHostedInvoice(String clerkUserId, String recordId) {
        RechargeOrderEntity order = requireOwnedBillingRecord(clerkUserId, recordId);
        String invoiceId = resolveStoredStripeInvoiceId(order);
        if (!hasText(invoiceId) && !hasRealStripeBillingReference(order)) {
            throw new BillingDomainException(
                    "BILLING_INVOICE_NOT_AVAILABLE",
                    "This billing record does not have a Stripe hosted invoice");
        }
        requireStripeConfigured();
        try {
            if (!hasText(invoiceId)) {
                invoiceId = resolveStripeInvoiceIdFromBillingReference(order);
            }
            if (!hasText(invoiceId)) {
                throw new BillingDomainException(
                        "BILLING_INVOICE_NOT_AVAILABLE",
                        "Stripe hosted invoice is not available for this billing record");
            }
            Invoice invoice = retrieveStripeInvoice(invoiceId);
            if (invoice == null || !hasText(invoice.getHostedInvoiceUrl())) {
                throw new BillingDomainException(
                        "BILLING_INVOICE_NOT_AVAILABLE",
                        "Stripe hosted invoice is not available for this billing record");
            }
            return BillingHostedInvoiceResult.builder()
                    .url(invoice.getHostedInvoiceUrl())
                    .build();
        } catch (StripeException e) {
            throw stripeFailure("Retrieve hosted invoice failed", e);
        }
    }

    private BillingRecordResult toBillingRecord(RechargeOrderEntity order) {
        return BillingRecordResult.builder()
                .id(hasText(order.getOrderNo())
                        ? order.getOrderNo()
                        : String.valueOf(order.getId()))
                .paidAt(firstNonNull(order.getPaidAt(), order.getCreatedAt(), order.getUpdatedAt()))
                .amountCents(order.getPriceCents() == null ? 0 : order.getPriceCents())
                .currency(normalizeCurrency(order.getCurrency()))
                .status(order.getStatus())
                .orderType(order.getOrderType())
                .hostedInvoiceAvailable(isHostedInvoiceCandidate(order))
                .build();
    }

    private boolean isHostedInvoiceCandidate(RechargeOrderEntity order) {
        return isSettledBillingStatus(order.getStatus())
                && (resolveStoredStripeInvoiceId(order) != null || hasResolvableHostedInvoiceReference(order));
    }

    private boolean hasResolvableHostedInvoiceReference(RechargeOrderEntity order) {
        if (!hasRealStripeBillingReference(order)) {
            return false;
        }
        String orderType = order.getOrderType() == null ? "" : order.getOrderType().trim().toLowerCase();
        return "subscription_initial".equals(orderType)
                || "subscription_upgrade".equals(orderType)
                || "subscription_upgrade_manual".equals(orderType)
                || "addon".equals(orderType);
    }

    private boolean isSettledBillingStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase();
        return "completed".equals(normalized)
                || "paid".equals(normalized)
                || "succeeded".equals(normalized)
                || "success".equals(normalized);
    }

    private RechargeOrderEntity requireOwnedBillingRecord(String clerkUserId, String recordId) {
        String normalizedRecordId = recordId == null ? "" : recordId.trim();
        if (!hasText(normalizedRecordId)) {
            throw new BillingDomainException("BILLING_RECORD_NOT_FOUND", "Billing record not found");
        }
        Long numericId = parseLongOrNull(normalizedRecordId);
        RechargeOrderEntity order = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                        .gt(RechargeOrderEntity::getPriceCents, 0)
                        .and(wrapper -> {
                            wrapper.eq(RechargeOrderEntity::getOrderNo, normalizedRecordId);
                            if (numericId != null) {
                                wrapper.or().eq(RechargeOrderEntity::getId, numericId);
                            }
                        })
                        .last("LIMIT 1"));
        if (order == null) {
            throw new BillingDomainException("BILLING_RECORD_NOT_FOUND", "Billing record not found");
        }
        return order;
    }

    private String resolveStoredStripeInvoiceId(RechargeOrderEntity order) {
        String invoiceId = order.getStripeInvoiceId();
        return isRealStripeReference(invoiceId) ? invoiceId : null;
    }

    private boolean hasRealStripeBillingReference(RechargeOrderEntity order) {
        return isRealStripeReference(order.getStripeSessionId())
                || isRealStripeReference(order.getStripeSubscriptionId());
    }

    private static boolean isRealStripeReference(String value) {
        return hasText(value) && !value.startsWith("mock_");
    }

    private String resolveStripeInvoiceIdFromBillingReference(RechargeOrderEntity order) throws StripeException {
        if (isRealStripeReference(order.getStripeSessionId())) {
            Session session = retrieveStripeCheckoutSession(order.getStripeSessionId());
            String sessionInvoiceId = resolveSessionInvoiceId(session);
            if (hasText(sessionInvoiceId)) {
                return sessionInvoiceId;
            }
            if (session != null && isRealStripeReference(session.getSubscription())) {
                String latestInvoiceId = resolveSubscriptionLatestInvoiceId(session.getSubscription());
                if (hasText(latestInvoiceId)) {
                    return latestInvoiceId;
                }
            }
        }
        if (isRealStripeReference(order.getStripeSubscriptionId())) {
            return resolveSubscriptionLatestInvoiceId(order.getStripeSubscriptionId());
        }
        return null;
    }

    private String resolveSessionInvoiceId(Session session) {
        if (session == null) {
            return null;
        }
        if (hasText(session.getInvoice())) {
            return session.getInvoice();
        }
        Invoice invoice = session.getInvoiceObject();
        return invoice == null ? null : invoice.getId();
    }

    private String resolveSubscriptionLatestInvoiceId(String subscriptionId) throws StripeException {
        Subscription subscription = retrieveStripeSubscription(subscriptionId);
        if (subscription == null) {
            return null;
        }
        if (hasText(subscription.getLatestInvoice())) {
            return subscription.getLatestInvoice();
        }
        Invoice invoice = subscription.getLatestInvoiceObject();
        return invoice == null ? null : invoice.getId();
    }

    private Long parseLongOrNull(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private CheckoutSessionResult createManualUpgradeCheckout(
            String clerkUserId,
            String customerEmail,
            SubscriptionPlanEntity targetPlan,
            UserSubscriptionEntity current,
            String requestedSuccessUrl,
            String requestedCancelUrl,
            String resumeToken) {
        SubscriptionPlanEntity currentPlan = requireCurrentPlan(current);
        PlanChangeAction action = classifyPlanChange(
                currentPlan.getPlanCode(),
                currentPlan.getTier(),
                currentPlan.getBillingInterval(),
                targetPlan.getPlanCode(),
                targetPlan.getTier(),
                targetPlan.getBillingInterval());
        if (action == PlanChangeAction.UNSUPPORTED) {
            throw new BillingDomainException(
                    "SUBSCRIPTION_STATE_INVALID",
                    "This plan change is not supported");
        }
        if (action != PlanChangeAction.IMMEDIATE_UPGRADE) {
            throw new BillingDomainException("INVALID_DOWNGRADE_TARGET", "Target plan is not an upgrade");
        }

        clearPendingUpgradeStateForRetry(current);
        String customerId = ensureStripeCustomer(current, clerkUserId, customerEmail);
        UpgradeCreditBasis creditBasis = resolveUpgradeCreditBasis(currentPlan, targetPlan, current);
        UpgradeChargeQuote quote = UpgradeChargeCalculator.quote(
                currentPlan,
                targetPlan,
                current.getQuotaPeriodStart() != null ? current.getQuotaPeriodStart() : current.getCurrentPeriodStart(),
                current.getCurrentPeriodEnd(),
                LocalDateTime.now(),
                creditBasis.netPaidCents(),
                creditBasis.sourceInvoiceId());
        String orderNo = generateOrderNo();

        try {
            releasePendingScheduleStateBeforeManualUpgradeCheckout(current);
            SessionCreateParams.PaymentIntentData paymentIntentData = SessionCreateParams.PaymentIntentData.builder()
                    .putMetadata("purchase_type", "subscription_upgrade_manual")
                    .putMetadata("upgrade_order_no", orderNo)
                    .putMetadata("clerk_user_id", clerkUserId)
                    .putMetadata("current_plan_code", currentPlan.getPlanCode())
                    .putMetadata("target_plan_code", targetPlan.getPlanCode())
                    .putMetadata("current_subscription_id", current.getStripeSubscriptionId())
                    .build();
            SessionCreateParams params = buildManualUpgradeCheckoutParams(
                    customerId,
                    clerkUserId,
                    currentPlan,
                    targetPlan,
                    current,
                    quote,
                    orderNo,
                    requestedSuccessUrl,
                    requestedCancelUrl,
                    resumeToken,
                    paymentIntentData);
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(buildManualUpgradeCheckoutIdempotencyKey(orderNo))
                    .build();
            Session session = createStripeCheckoutSession(params, options);
            try {
                insertPendingUpgradeOrder(orderNo, clerkUserId, currentPlan, targetPlan, current, quote, session);
                markPendingUpgradeCheckout(current, orderNo, session);
            } catch (RuntimeException e) {
                expireCheckoutAfterPersistenceFailure(session, e);
                throw e;
            }

            return CheckoutSessionResult.builder()
                    .checkoutKind("session")
                    .sessionId(session.getId())
                    .referenceId(session.getId())
                    .checkoutUrl(session.getUrl())
                    .expiresAt(session.getExpiresAt())
                    .resumeToken(resumeToken)
                    .quotedAmountCents(quote.getAmountCents())
                    .upgradeChargeType(quote.getChargeType())
                    .targetPlanCode(targetPlan.getPlanCode())
                    .build();
        } catch (StripeException e) {
            throw stripeFailure("Create manual subscription upgrade Checkout failed", e);
        }
    }

    private void expireCheckoutAfterPersistenceFailure(
            Session session,
            RuntimeException persistenceFailure) {
        if (session == null || !hasText(session.getId())) {
            return;
        }
        try {
            expireStripeCheckoutSession(session);
            log.warn(
                    "Expired Stripe Checkout after local billing persistence failure: session={}",
                    session.getId());
        } catch (StripeException | RuntimeException compensationFailure) {
            persistenceFailure.addSuppressed(compensationFailure);
            log.error(
                    "Failed to expire Stripe Checkout after local billing persistence failure: "
                            + "session={}",
                    session.getId(),
                    compensationFailure);
        }
    }

    private void releasePendingScheduleStateBeforeManualUpgradeCheckout(
            UserSubscriptionEntity current) throws StripeException {
        if (current == null || !hasText(current.getStripeSubscriptionId())) {
            return;
        }
        Subscription subscription = retrieveStripeSubscription(current.getStripeSubscriptionId());
        String scheduleId = firstNonBlank(current.getStripeScheduleId(), subscription.getSchedule());
        boolean hasPendingState = scheduleId != null
                || hasText(current.getPendingPlanCode())
                || current.getPendingEffectiveAt() != null;
        if (!hasPendingState) {
            resumeCancellationBeforePaidPlanChangeIfNeeded(current, subscription);
            return;
        }
        if (scheduleId != null) {
            releasePendingScheduleIfPresent(current, subscription);
            subscription = retrieveStripeSubscription(current.getStripeSubscriptionId());
        }
        clearPendingScheduleState(current);
        resumeCancellationBeforePaidPlanChangeIfNeeded(current, subscription);
    }

    private UpgradeCreditBasis resolveUpgradeCreditBasis(
            SubscriptionPlanEntity currentPlan,
            SubscriptionPlanEntity targetPlan,
            UserSubscriptionEntity current) {
        if (!"year".equals(currentPlan.getBillingInterval())
                || !"year".equals(targetPlan.getBillingInterval())) {
            return new UpgradeCreditBasis(0, null);
        }
        try {
            Subscription subscription = retrieveStripeSubscription(current.getStripeSubscriptionId());
            String invoiceId = subscription == null ? null : subscription.getLatestInvoice();
            if (!hasText(invoiceId)) {
                throw new BillingDomainException(
                        "UPGRADE_QUOTE_UNAVAILABLE",
                        "The settled source invoice is unavailable; retry after billing sync");
            }
            Invoice invoice = retrieveStripeInvoice(invoiceId);
            long amountPaid = invoice.getAmountPaid() == null ? 0L : invoice.getAmountPaid();
            Long subtotal = invoice.getSubtotalExcludingTax() != null
                    ? invoice.getSubtotalExcludingTax()
                    : invoice.getSubtotal();
            long netBeforeRefund = subtotal == null
                    ? amountPaid
                    : Math.min(amountPaid, Math.max(0L, subtotal));
            long refunded = 0L;
            if (hasText(invoice.getCharge())) {
                Charge charge = retrieveStripeCharge(invoice.getCharge());
                refunded = charge.getAmountRefunded() == null ? 0L : charge.getAmountRefunded();
            }
            long netPaid = Math.max(0L, netBeforeRefund - refunded);
            return new UpgradeCreditBasis((int) Math.min(Integer.MAX_VALUE, netPaid), invoiceId);
        } catch (StripeException e) {
            throw stripeFailure("Resolve settled invoice for upgrade quote failed", e);
        }
    }

    private Subscription resumeCancellationBeforePaidPlanChangeIfNeeded(
            UserSubscriptionEntity current,
            Subscription subscription) throws StripeException {
        if (current == null || subscription == null) {
            return subscription;
        }
        if (!Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd())
                && !Boolean.TRUE.equals(current.getCancelAtPeriodEnd())) {
            return subscription;
        }
        Subscription resumed = updateStripeSubscription(subscription, SubscriptionUpdateParams.builder()
                .setCancelAtPeriodEnd(false)
                .build());
        syncCancellationFields(current, resumed);
        return resumed;
    }

    void clearPendingUpgradeStateForRetry(UserSubscriptionEntity current) {
        if (current == null || !hasText(current.getClerkUserId())) {
            return;
        }
        expirePendingUpgradeCheckoutSessions(current.getClerkUserId());
        LocalDateTime now = LocalDateTime.now();
        rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getClerkUserId, current.getClerkUserId())
                .in(RechargeOrderEntity::getOrderType, List.of("subscription_upgrade", "subscription_upgrade_manual"))
                .in(RechargeOrderEntity::getStatus, List.of("pending", "pending_checkout", "checkout_created"))
                .set(RechargeOrderEntity::getStatus, "checkout_expired")
                .set(RechargeOrderEntity::getFailureReason, "superseded_by_new_upgrade")
                .set(RechargeOrderEntity::getUpdatedAt, now));
        if (!hasText(current.getPendingPlanCode()) && !hasText(current.getPendingUpgradeOrderNo())) {
            return;
        }
        userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, current.getId())
                .set(UserSubscriptionEntity::getPendingPlanCode, null)
                .set(UserSubscriptionEntity::getPendingEffectiveAt, null)
                .set(UserSubscriptionEntity::getPendingUpgradeOrderNo, null)
                .set(UserSubscriptionEntity::getPendingUpgradeExpiresAt, null)
                .set(UserSubscriptionEntity::getUpdatedAt, now));
        current.setPendingPlanCode(null);
        current.setPendingEffectiveAt(null);
        current.setPendingUpgradeOrderNo(null);
        current.setPendingUpgradeExpiresAt(null);
        current.setUpdatedAt(now);
    }

    private void expirePendingUpgradeCheckoutSessions(String clerkUserId) {
        List<RechargeOrderEntity> pendingOrders = rechargeOrderMapper.selectList(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                        .in(RechargeOrderEntity::getOrderType,
                                List.of("subscription_upgrade", "subscription_upgrade_manual"))
                        .in(RechargeOrderEntity::getStatus,
                                List.of("pending", "pending_checkout", "checkout_created"))
                        .isNotNull(RechargeOrderEntity::getStripeSessionId));
        for (RechargeOrderEntity order : pendingOrders) {
            if (!hasText(order.getStripeSessionId())) {
                continue;
            }
            try {
                Session session = retrieveStripeCheckoutSession(order.getStripeSessionId());
                if ("complete".equals(session.getStatus()) || "paid".equals(session.getPaymentStatus())) {
                    throw new BillingDomainException(
                            "SUBSCRIPTION_CHANGE_PENDING",
                            "The previous upgrade payment is still being applied");
                }
                if ("open".equals(session.getStatus())) {
                    expireStripeCheckoutSession(session);
                }
            } catch (StripeException e) {
                throw stripeFailure("Expire previous upgrade Checkout failed", e);
            }
        }
    }

    @Override
    public SubscriptionResult getCurrentSubscription(String clerkUserId) {
        UserSubscriptionEntity entity = findByUser(clerkUserId);
        BillingPlan effectivePlan = getEffectivePlanOrFree(clerkUserId);
        boolean isAdmin = userRepository.findByClerkUserId(clerkUserId)
                .map(user -> Boolean.TRUE.equals(user.getIsAdmin()))
                .orElse(false);
        boolean isQuotaVip = quotaVipAccessService.isQuotaVip(clerkUserId);
        boolean unlimited = isAdmin || isQuotaVip;
        SubscriptionResult result = entity == null ? freeSubscription() : toResult(entity);
        result.setIsAdmin(isAdmin);
        result.setIsQuotaVip(isQuotaVip);
        result.setEffectiveMaxFiles(unlimited ? null : effectivePlan.getMaxFiles());
        result.setEffectiveMaxFollowupEdits(unlimited ? null : effectivePlan.getMaxFollowupEdits());
        result.setEffectiveAllowedOutputTypes(unlimited
                ? List.of("writing", "ppt", "coding")
                : parseAllowedOutputTypes(effectivePlan.getAllowedOutputTypes()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubscriptionResult cancelAtPeriodEnd(String clerkUserId) {
        return setCancelAtPeriodEnd(clerkUserId, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubscriptionResult resumeSubscription(String clerkUserId) {
        return setCancelAtPeriodEnd(clerkUserId, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubscriptionResult changeSubscription(String clerkUserId, String targetPlanCode) {
        requireStripeConfigured();
        String normalizedTargetPlanCode = normalizePlanCode(targetPlanCode);
        SubscriptionPlanEntity targetPlan = requirePlan(normalizedTargetPlanCode);
        UserSubscriptionEntity current = requireCurrentSubscription(clerkUserId);
        if (normalizedTargetPlanCode.equals(current.getPlanCode())) {
            return toResult(current);
        }
        SubscriptionPlanEntity currentPlan = requireCurrentPlan(current);
        return switch (classifyPlanChange(
                currentPlan.getPlanCode(),
                currentPlan.getTier(),
                currentPlan.getBillingInterval(),
                targetPlan.getPlanCode(),
                targetPlan.getTier(),
                targetPlan.getBillingInterval())) {
            case NOOP -> toResult(current);
            case IMMEDIATE_UPGRADE -> throw new BillingDomainException(
                    "UPGRADE_REQUIRES_CHECKOUT",
                    "Immediate upgrades must use /v1/payment/subscription-checkout");
            case DEFERRED_CHANGE -> downgradeSubscription(clerkUserId, normalizedTargetPlanCode);
            case UNSUPPORTED -> throw new BillingDomainException(
                    "SUBSCRIPTION_STATE_INVALID",
                    "This plan change is not supported");
        };
    }

    @Override
    public SubscriptionResult downgradeSubscription(String clerkUserId, String targetPlanCode) {
        requireStripeConfigured();
        String normalizedTargetPlanCode = normalizePlanCode(targetPlanCode);
        SubscriptionPlanEntity targetPlan = requirePlan(normalizedTargetPlanCode);
        UserSubscriptionEntity current = requireCurrentSubscription(clerkUserId);
        if (normalizedTargetPlanCode.equals(current.getPlanCode())) {
            return toResult(current);
        }
        SubscriptionPlanEntity currentPlan = requireCurrentPlan(current);
        PlanChangeAction action = classifyPlanChange(
                currentPlan.getPlanCode(),
                currentPlan.getTier(),
                currentPlan.getBillingInterval(),
                targetPlan.getPlanCode(),
                targetPlan.getTier(),
                targetPlan.getBillingInterval());
        if (action == PlanChangeAction.UNSUPPORTED) {
            throw new BillingDomainException("SUBSCRIPTION_STATE_INVALID", "This plan change is not supported");
        }
        if (action == PlanChangeAction.IMMEDIATE_UPGRADE) {
            throw new BillingDomainException("INVALID_DOWNGRADE_TARGET", "Target plan is not a downgrade");
        }
        if (current.getCurrentPeriodEnd() == null) {
            throw new BillingDomainException("SUBSCRIPTION_STATE_INVALID", "Current period end is missing");
        }

        try {
            Subscription stripeSubscription = retrieveStripeSubscription(current.getStripeSubscriptionId());
            stripeSubscription = resumeCancellationBeforePaidPlanChangeIfNeeded(current, stripeSubscription);
            SubscriptionSchedule schedule = upsertDowngradeSchedule(
                    clerkUserId,
                    current,
                    stripeSubscription,
                    targetPlan);
            LocalDateTime pendingEffectiveAt = fromEpoch(stripeSubscription.getCurrentPeriodEnd());
            LocalDateTime now = LocalDateTime.now();
            userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                    .eq(UserSubscriptionEntity::getId, current.getId())
                    .set(UserSubscriptionEntity::getStripeScheduleId, schedule.getId())
                    .set(UserSubscriptionEntity::getPendingPlanCode, normalizedTargetPlanCode)
                    .set(UserSubscriptionEntity::getPendingEffectiveAt, pendingEffectiveAt)
                    .set(UserSubscriptionEntity::getUpdatedAt, now));
            current.setStripeScheduleId(schedule.getId());
            current.setPendingPlanCode(normalizedTargetPlanCode);
            current.setPendingEffectiveAt(pendingEffectiveAt);
            current.setUpdatedAt(now);
            return toResult(current);
        } catch (StripeException e) {
            throw stripeFailure("Downgrade subscription failed", e);
        }
    }

    @Override
    public BillingPlan getEffectivePlanOrFree(String clerkUserId) {
        UserSubscriptionEntity entity = findByUser(clerkUserId);
        if (entity != null
                && entity.getPlanCode() != null
                && ("active".equals(entity.getStatus()) || "trialing".equals(entity.getStatus()))) {
            return toPlan(requireRuntimePlan(entity.getPlanCode()));
        }
        // Free hard-cut: unpaid users get zero entitlements (not synthetic Free).
        return introTrialEnabled ? BillingPlan.lapsedPlan() : BillingPlan.freePlan();
    }

    @Override
    public boolean isPaidMember(String clerkUserId) {
        UserSubscriptionEntity entity = findByUser(clerkUserId);
        if (entity == null || entity.getStripeSubscriptionId() == null) {
            return false;
        }
        return "active".equals(entity.getStatus()) || "trialing".equals(entity.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void fulfillIntroTrialSubscription(
            String clerkUserId,
            String stripeCustomerId,
            String stripeSubscriptionId) {
        if (!introTrialEnabled || !hasText(clerkUserId) || !hasText(stripeSubscriptionId)) {
            return;
        }
        UserSubscriptionEntity current = userSubscriptionMapper.selectByUserForUpdate(clerkUserId);
        if (current == null) {
            current = getOrCreateUserSubscription(clerkUserId);
            current = userSubscriptionMapper.selectByUserForUpdate(clerkUserId);
        }
        if (current == null) {
            throw new BillingDomainException("SUBSCRIPTION_NOT_FOUND", "User subscription not found");
        }
        markIntroTrialUsed(current, stripeCustomerId);
        ensureIntroTrialConversionSchedule(current, stripeSubscriptionId);
    }

    private void assertLapsedCheckoutAllowed(
            UserSubscriptionEntity userSubscription,
            SubscriptionPlanEntity plan) {
        if (!introTrialEnabled) {
            return;
        }
        if (isIntroTrialPlan(plan)) {
            return;
        }
        // Unpaid users may skip Trial by buying Plus / Pro only.
        if ("plus".equalsIgnoreCase(plan.getTier()) || "pro".equalsIgnoreCase(plan.getTier())) {
            return;
        }
        if ("basic".equalsIgnoreCase(plan.getTier()) && !allowDirectPurchaseBasic) {
            throw new BillingDomainException(
                    "BASIC_REQUIRES_TRIAL",
                    "Basic requires completing Basic trial first; buy Plus/Pro to skip");
        }
    }

    private void assertSellableIntroTrialPlan(SubscriptionPlanEntity plan) {
        if (isIntroTrialPlan(plan)
                && !IntroTrialPlans.isSellableIntroTrialPlanCode(plan.getPlanCode())) {
            throw new BillingDomainException(
                    "INVALID_PLAN",
                    "Basic trial offer is no longer available: " + plan.getPlanCode());
        }
    }

    private void assertCurrentSubscriptionCannotBuyIntroTrial(
            UserSubscriptionEntity current,
            SubscriptionPlanEntity targetPlan) {
        if (!isIntroTrialPlan(targetPlan) || current == null) {
            return;
        }
        if ("active".equalsIgnoreCase(current.getStatus())
                || "trialing".equalsIgnoreCase(current.getStatus())) {
            throw new BillingDomainException(
                    "SUBSCRIPTION_STATE_INVALID",
                    "Cannot switch an active subscription to Basic trial");
        }
    }

    private void rejectUnsupportedPaidPlanTarget(
            UserSubscriptionEntity current,
            SubscriptionPlanEntity targetPlan) {
        if (!introTrialEnabled) {
            return;
        }
        if (isIntroTrialPlan(targetPlan)) {
            throw new BillingDomainException(
                    "SUBSCRIPTION_STATE_INVALID",
                    "Cannot switch an active subscription to Basic trial");
        }
        if (current != null
                && IntroTrialPlans.isIntroTrialPlanCode(current.getPlanCode())
                && IntroTrialPlans.isBasicPaidTier(targetPlan.getTier())
                && !isIntroTrialPlan(targetPlan)) {
            throw new BillingDomainException(
                    "SUBSCRIPTION_STATE_INVALID",
                    "Basic renews automatically after the paid trial");
        }
    }

    private void assertIntroTrialEligible(UserSubscriptionEntity current, String stripeCustomerId) {
        if (current != null && current.getIntroTrialUsedAt() != null) {
            throw new BillingDomainException(
                    "TRIAL_ALREADY_USED",
                    "Basic trial can only be used once per Stripe customer");
        }
        if (hasPaidIntroTrialOrder(current == null ? null : current.getClerkUserId())) {
            throw new BillingDomainException(
                    "TRIAL_ALREADY_USED",
                    "Basic trial can only be used once per Stripe customer");
        }
        if (isIntroTrialUsedOnStripeCustomer(stripeCustomerId)) {
            throw new BillingDomainException(
                    "TRIAL_ALREADY_USED",
                    "Basic trial can only be used once per Stripe customer");
        }
    }

    private boolean hasPaidIntroTrialOrder(String clerkUserId) {
        if (!hasText(clerkUserId)) {
            return false;
        }
        Long count = rechargeOrderMapper.selectCount(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                        .eq(RechargeOrderEntity::getOrderType, IntroTrialPlans.ORDER_TYPE_INTRO_TRIAL)
                        .in(RechargeOrderEntity::getStatus, List.of("paid", "completed", "switching")));
        return count != null && count > 0;
    }

    private boolean isIntroTrialUsedOnStripeCustomer(String stripeCustomerId) {
        if (!hasText(stripeCustomerId) || !isRealStripeReference(stripeCustomerId)) {
            return false;
        }
        try {
            Customer customer = Customer.retrieve(stripeCustomerId);
            if (customer.getMetadata() == null) {
                return false;
            }
            return "true".equalsIgnoreCase(
                    customer.getMetadata().get(IntroTrialPlans.STRIPE_CUSTOMER_META_INTRO_TRIAL_USED));
        } catch (StripeException e) {
            log.warn("Failed to read Stripe customer intro trial metadata: customer={}", stripeCustomerId, e);
            return false;
        }
    }

    private void markIntroTrialUsed(UserSubscriptionEntity current, String stripeCustomerId) {
        LocalDateTime now = LocalDateTime.now();
        if (current.getIntroTrialUsedAt() == null) {
            userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                    .eq(UserSubscriptionEntity::getId, current.getId())
                    .isNull(UserSubscriptionEntity::getIntroTrialUsedAt)
                    .set(UserSubscriptionEntity::getIntroTrialUsedAt, now)
                    .set(UserSubscriptionEntity::getSubscriptionPhase, IntroTrialPlans.PHASE_INTRO)
                    .set(UserSubscriptionEntity::getUpdatedAt, now));
            current.setIntroTrialUsedAt(now);
            current.setSubscriptionPhase(IntroTrialPlans.PHASE_INTRO);
        }
        String customerId = firstNonBlank(stripeCustomerId, current.getStripeCustomerId());
        if (!hasText(customerId) || !isRealStripeReference(customerId)) {
            return;
        }
        try {
            Customer.retrieve(customerId).update(CustomerUpdateParams.builder()
                    .putMetadata(IntroTrialPlans.STRIPE_CUSTOMER_META_INTRO_TRIAL_USED, "true")
                    .build());
        } catch (StripeException e) {
            throw stripeFailure("Mark Stripe customer intro trial used failed", e);
        }
    }

    private void ensureIntroTrialConversionSchedule(
            UserSubscriptionEntity current,
            String stripeSubscriptionId) {
        String conversionPlanCode = resolveConversionPlanCodeForUser(current);
        SubscriptionPlanEntity conversionPlan = requirePlan(conversionPlanCode, true);
        try {
            Subscription stripeSubscription = retrieveStripeSubscription(stripeSubscriptionId);
            SubscriptionSchedule schedule;
            String existingScheduleId = firstNonBlank(
                    stripeSubscription.getSchedule(), current.getStripeScheduleId());
            if (hasText(existingScheduleId)) {
                schedule = retrieveReusableSchedule(existingScheduleId);
                if (schedule == null) {
                    // Stale/canceled schedule left on the subscription mirror — recreate.
                    schedule = createStripeSubscriptionSchedule(
                            SubscriptionScheduleCreateParams.builder()
                                    .setFromSubscription(stripeSubscriptionId)
                                    .build(),
                            RequestOptions.builder()
                                    .setIdempotencyKey("intro-trial-schedule:create:" + stripeSubscriptionId)
                                    .build());
                }
            } else {
                schedule = createStripeSubscriptionSchedule(
                        SubscriptionScheduleCreateParams.builder()
                                .setFromSubscription(stripeSubscriptionId)
                                .build(),
                        RequestOptions.builder()
                                .setIdempotencyKey("intro-trial-schedule:create:" + stripeSubscriptionId)
                                .build());
            }
            // Always (re)apply Phase1=trial / Phase2=standard Basic so a wrong earlier
            // update (e.g. pending_plan_code=trial SKU) cannot stick.
            schedule = applyIntroTrialConversionPhases(
                    schedule,
                    stripeSubscription,
                    conversionPlan,
                    current.getClerkUserId(),
                    conversionPlanCode);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime pendingEffectiveAt = fromEpoch(stripeSubscription.getCurrentPeriodEnd());
            userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                    .eq(UserSubscriptionEntity::getId, current.getId())
                    .set(UserSubscriptionEntity::getStripeScheduleId, schedule.getId())
                    .set(UserSubscriptionEntity::getPendingPlanCode, conversionPlanCode)
                    .set(UserSubscriptionEntity::getPendingEffectiveAt, pendingEffectiveAt)
                    .set(UserSubscriptionEntity::getSubscriptionPhase, IntroTrialPlans.PHASE_INTRO)
                    .set(UserSubscriptionEntity::getUpdatedAt, now));
            current.setStripeScheduleId(schedule.getId());
            current.setPendingPlanCode(conversionPlanCode);
            current.setPendingEffectiveAt(pendingEffectiveAt);
            current.setSubscriptionPhase(IntroTrialPlans.PHASE_INTRO);
        } catch (StripeException e) {
            throw stripeFailure("Create intro trial conversion schedule failed", e);
        }
    }

    private SubscriptionSchedule applyIntroTrialConversionPhases(
            SubscriptionSchedule schedule,
            Subscription stripeSubscription,
            SubscriptionPlanEntity conversionPlan,
            String clerkUserId,
            String conversionPlanCode) throws StripeException {
        SubscriptionItem item = requireSingleSubscriptionItem(stripeSubscription);
        Long currentPhaseStart = currentPhaseStart(schedule, stripeSubscription);
        Long currentPhaseEnd = stripeSubscription.getCurrentPeriodEnd();
        Long quantity = item.getQuantity() == null ? 1L : item.getQuantity();
        String trialPriceId = item.getPrice().getId();
        String conversionPriceId = conversionPlan.getStripePriceId();
        SubscriptionScheduleUpdateParams updateParams = SubscriptionScheduleUpdateParams.builder()
                .setEndBehavior(SubscriptionScheduleUpdateParams.EndBehavior.RELEASE)
                .setProrationBehavior(SubscriptionScheduleUpdateParams.ProrationBehavior.NONE)
                .addPhase(SubscriptionScheduleUpdateParams.Phase.builder()
                        .setStartDate(currentPhaseStart)
                        .setEndDate(currentPhaseEnd)
                        .setProrationBehavior(SubscriptionScheduleUpdateParams.Phase.ProrationBehavior.NONE)
                        .addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder()
                                .setPrice(trialPriceId)
                                .setQuantity(quantity)
                                .build())
                        .build())
                .addPhase(SubscriptionScheduleUpdateParams.Phase.builder()
                        .setStartDate(currentPhaseEnd)
                        .setProrationBehavior(SubscriptionScheduleUpdateParams.Phase.ProrationBehavior.NONE)
                        .addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder()
                                .setPrice(conversionPriceId)
                                .setQuantity(1L)
                                .build())
                        .build())
                .putMetadata("clerk_user_id", clerkUserId)
                .putMetadata("pending_plan_code", conversionPlanCode)
                .putMetadata("change_type", IntroTrialPlans.SCHEDULE_CHANGE_TYPE_INTRO_CONVERSION)
                .build();
        return updateStripeSubscriptionSchedule(
                schedule,
                updateParams,
                RequestOptions.builder()
                        .setIdempotencyKey("intro-trial-schedule:fix:" + schedule.getId()
                                + ":" + conversionPlanCode + ":" + conversionPriceId + ":" + currentPhaseEnd)
                        .build());
    }

    private boolean isIntroTrialPlan(SubscriptionPlanEntity plan) {
        return plan != null
                && IntroTrialPlans.isIntroTrialPlan(plan.getPlanCode(), plan.getOfferKind());
    }

    private String resolveConversionPlanCode(SubscriptionPlanEntity trialPlan) {
        String trialPlanCode = trialPlan == null ? null : trialPlan.getPlanCode();
        String fromCatalog = trialPlan == null ? null : trialPlan.getConvertsToPlanCode();
        String resolved = IntroTrialPlans.sanitizeConversionPlanCode(fromCatalog, trialPlanCode);
        if (hasText(resolved) && !IntroTrialPlans.isIntroTrialPlanCode(resolved)) {
            return resolved;
        }
        return IntroTrialPlans.sanitizeConversionPlanCode(introTrialConversionPlanCode, trialPlanCode);
    }

    /**
     * Resolve standard Basic plan after paid trial. Never trust a pending/trial SKU
     * as the conversion target (checkout link previously wrote trial plan into pending).
     */
    private String resolveConversionPlanCodeForUser(UserSubscriptionEntity current) {
        String trialPlanCode = current == null ? null : current.getPlanCode();
        if (current != null && hasText(current.getPendingPlanCode())
                && !IntroTrialPlans.isIntroTrialPlanCode(current.getPendingPlanCode())) {
            return current.getPendingPlanCode();
        }
        if (hasText(trialPlanCode)) {
            SubscriptionPlanEntity trialPlan = subscriptionPlanMapper.selectOne(
                    new LambdaQueryWrapper<SubscriptionPlanEntity>()
                            .eq(SubscriptionPlanEntity::getPlanCode, trialPlanCode)
                            .last("LIMIT 1"));
            if (trialPlan != null) {
                return resolveConversionPlanCode(trialPlan);
            }
            return IntroTrialPlans.defaultConversionPlanCode(trialPlanCode);
        }
        return IntroTrialPlans.sanitizeConversionPlanCode(introTrialConversionPlanCode, null);
    }

    private LocalDateTime resolvePeriodEnd(LocalDateTime start, String billingInterval) {
        if ("year".equals(billingInterval)) {
            return start.plusYears(1);
        }
        if ("week".equals(billingInterval)) {
            return start.plusWeeks(1);
        }
        return start.plusMonths(1);
    }

    private SubscriptionResult setCancelAtPeriodEnd(String clerkUserId, boolean cancel) {
        requireStripeConfigured();
        UserSubscriptionEntity current = requireCurrentSubscription(clerkUserId);
        try {
            Subscription subscription = Subscription.retrieve(current.getStripeSubscriptionId());
            String scheduleId = firstNonBlank(current.getStripeScheduleId(), subscription.getSchedule());
            boolean shouldClearPendingScheduleState =
                    shouldClearPendingScheduleStateBeforeCancellation(cancel, scheduleId, current);
            if (scheduleId != null) {
                releasePendingScheduleIfPresent(current, subscription);
                subscription = Subscription.retrieve(current.getStripeSubscriptionId());
            }
            if (shouldClearPendingScheduleState) {
                clearPendingScheduleState(current);
            }
            if (canSkipCancellationUpdate(current.getCancelAtPeriodEnd(), cancel, current.getStripeScheduleId(), subscription.getSchedule())) {
                return toResult(current);
            }
            Subscription updated = subscription.update(SubscriptionUpdateParams.builder()
                    .setCancelAtPeriodEnd(cancel)
                    .build());
            syncCancellationFields(current, updated);
            return toResult(current);
        } catch (StripeException e) {
            throw stripeFailure(cancel ? "Cancel subscription failed" : "Resume subscription failed", e);
        }
    }

    static boolean canSkipCancellationUpdate(
            Boolean currentCancelAtPeriodEnd,
            boolean requestedCancelAtPeriodEnd,
            String localScheduleId,
            String remoteScheduleId) {
        return Boolean.valueOf(requestedCancelAtPeriodEnd).equals(currentCancelAtPeriodEnd)
                && firstNonBlank(localScheduleId, remoteScheduleId) == null;
    }

    static boolean shouldClearPendingScheduleStateBeforeCancellation(
            boolean cancelAtPeriodEnd,
            String scheduleId,
            UserSubscriptionEntity current) {
        if (scheduleId != null && !scheduleId.isBlank()) {
            return true;
        }
        return cancelAtPeriodEnd
                && current != null
                && ((current.getPendingPlanCode() != null && !current.getPendingPlanCode().isBlank())
                || current.getPendingEffectiveAt() != null);
    }

    private SubscriptionSchedule upsertDowngradeSchedule(
            String clerkUserId,
            UserSubscriptionEntity current,
            Subscription stripeSubscription,
            SubscriptionPlanEntity targetPlan) throws StripeException {
        String scheduleId = firstNonBlank(current.getStripeScheduleId(), stripeSubscription.getSchedule());
        Subscription scheduleSubscription = stripeSubscription;
        if (scheduleId != null && !scheduleId.isBlank()) {
            releaseScheduleIfReusable(scheduleId);
            clearPendingScheduleState(current);
            scheduleSubscription = retrieveStripeSubscription(stripeSubscription.getId());
            scheduleId = null;
        }
        SubscriptionSchedule schedule = retrieveReusableSchedule(scheduleId);
        boolean createdReplacementSchedule = false;
        if (schedule == null) {
            RequestOptions createOptions = RequestOptions.builder()
                    .setIdempotencyKey("downgrade-schedule:create:" + scheduleSubscription.getId()
                            + ":" + targetPlan.getPlanCode() + ":" + stripeSubscription.getCurrentPeriodEnd())
                    .build();
            schedule = createStripeSubscriptionSchedule(
                    buildDowngradeScheduleCreateParams(scheduleSubscription.getId()),
                    createOptions);
            createdReplacementSchedule = true;
        }

        SubscriptionItem item = requireSingleSubscriptionItem(scheduleSubscription);
        Long currentPhaseStart = currentPhaseStart(schedule, scheduleSubscription);
        Long currentPhaseEnd = scheduleSubscription.getCurrentPeriodEnd();
        Long quantity = item.getQuantity() == null ? 1L : item.getQuantity();
        SubscriptionScheduleUpdateParams updateParams = buildDowngradeScheduleUpdateParams(
                clerkUserId,
                targetPlan,
                currentPhaseStart,
                currentPhaseEnd,
                item.getPrice().getId(),
                quantity);
        RequestOptions updateOptions = RequestOptions.builder()
                .setIdempotencyKey("downgrade-schedule:update:" + schedule.getId() + ":"
                        + targetPlan.getPlanCode() + ":" + currentPhaseEnd)
                .build();
        try {
            return updateStripeSubscriptionSchedule(schedule, updateParams, updateOptions);
        } catch (StripeException e) {
            if (createdReplacementSchedule && schedule != null && schedule.getId() != null) {
                releaseScheduleIfReusable(schedule.getId());
            }
            throw e;
        }
    }

    static SubscriptionScheduleCreateParams buildDowngradeScheduleCreateParams(String stripeSubscriptionId) {
        return SubscriptionScheduleCreateParams.builder()
                .setFromSubscription(stripeSubscriptionId)
                .build();
    }

    static SubscriptionScheduleUpdateParams buildDowngradeScheduleUpdateParams(
            String clerkUserId,
            SubscriptionPlanEntity targetPlan,
            Long currentPhaseStart,
            Long currentPhaseEnd,
            String currentPriceId,
            Long quantity) {
        return SubscriptionScheduleUpdateParams.builder()
                .setEndBehavior(SubscriptionScheduleUpdateParams.EndBehavior.RELEASE)
                .setProrationBehavior(SubscriptionScheduleUpdateParams.ProrationBehavior.NONE)
                .addPhase(SubscriptionScheduleUpdateParams.Phase.builder()
                        .setStartDate(currentPhaseStart)
                        .setEndDate(currentPhaseEnd)
                        .setProrationBehavior(SubscriptionScheduleUpdateParams.Phase.ProrationBehavior.NONE)
                        .addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder()
                                .setPrice(currentPriceId)
                                .setQuantity(quantity)
                                .build())
                        .build())
                .addPhase(SubscriptionScheduleUpdateParams.Phase.builder()
                        .setStartDate(currentPhaseEnd)
                        .setIterations(1L)
                        .setProrationBehavior(SubscriptionScheduleUpdateParams.Phase.ProrationBehavior.NONE)
                        .addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder()
                                .setPrice(targetPlan.getStripePriceId())
                                .setQuantity(1L)
                                .build())
                        .build())
                .putMetadata("clerk_user_id", clerkUserId)
                .putMetadata("pending_plan_code", targetPlan.getPlanCode())
                .putMetadata("change_type", "downgrade")
                .build();
    }

    private Long currentPhaseStart(SubscriptionSchedule schedule, Subscription stripeSubscription) {
        if (schedule.getCurrentPhase() != null && schedule.getCurrentPhase().getStartDate() != null) {
            return schedule.getCurrentPhase().getStartDate();
        }
        if (stripeSubscription.getCurrentPeriodStart() != null) {
            return stripeSubscription.getCurrentPeriodStart();
        }
        return Instant.now().getEpochSecond();
    }

    private SubscriptionSchedule retrieveReusableSchedule(String scheduleId) throws StripeException {
        if (scheduleId == null || scheduleId.isBlank()) {
            return null;
        }
        SubscriptionSchedule schedule = retrieveStripeSubscriptionSchedule(scheduleId);
        return switch (schedule.getStatus()) {
            case "active", "not_started" -> schedule;
            default -> null;
        };
    }

    private void releaseScheduleIfReusable(String scheduleId) throws StripeException {
        SubscriptionSchedule schedule = retrieveReusableSchedule(scheduleId);
        if (schedule == null) {
            return;
        }
        releaseStripeSubscriptionSchedule(schedule, SubscriptionScheduleReleaseParams.builder()
                .setPreserveCancelDate(false)
                .build());
    }

    private void releasePendingScheduleIfPresent(
            UserSubscriptionEntity current,
            Subscription stripeSubscription) throws StripeException {
        String scheduleId = firstNonBlank(current.getStripeScheduleId(), stripeSubscription.getSchedule());
        if (scheduleId == null) {
            return;
        }
        releaseScheduleIfReusable(scheduleId);
    }

    private SubscriptionItem requireSingleSubscriptionItem(Subscription stripeSubscription) {
        if (stripeSubscription.getItems() == null
                || stripeSubscription.getItems().getData() == null
                || stripeSubscription.getItems().getData().size() != 1) {
            throw new BillingDomainException("INVALID_SUBSCRIPTION_ITEMS", "Subscription must contain one item");
        }
        SubscriptionItem item = stripeSubscription.getItems().getData().get(0);
        if (item.getPrice() == null || item.getPrice().getId() == null) {
            throw new BillingDomainException("INVALID_SUBSCRIPTION_ITEMS", "Subscription item price is missing");
        }
        return item;
    }

    private void clearPendingScheduleState(UserSubscriptionEntity current) {
        LocalDateTime now = LocalDateTime.now();
        userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, current.getId())
                .set(UserSubscriptionEntity::getStripeScheduleId, null)
                .set(UserSubscriptionEntity::getPendingPlanCode, null)
                .set(UserSubscriptionEntity::getPendingEffectiveAt, null)
                .set(UserSubscriptionEntity::getUpdatedAt, now));
        current.setStripeScheduleId(null);
        current.setPendingPlanCode(null);
        current.setPendingEffectiveAt(null);
        current.setUpdatedAt(now);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private void syncCancellationFields(UserSubscriptionEntity current, Subscription updated) {
        LocalDateTime now = LocalDateTime.now();
        userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, current.getId())
                .set(UserSubscriptionEntity::getCancelAtPeriodEnd, updated.getCancelAtPeriodEnd())
                .set(UserSubscriptionEntity::getStatus, updated.getStatus())
                .set(UserSubscriptionEntity::getCurrentPeriodStart, fromEpoch(updated.getCurrentPeriodStart()))
                .set(UserSubscriptionEntity::getCurrentPeriodEnd, fromEpoch(updated.getCurrentPeriodEnd()))
                .set(UserSubscriptionEntity::getLastSyncedAt, now)
                .set(UserSubscriptionEntity::getUpdatedAt, now));
        current.setCancelAtPeriodEnd(Boolean.TRUE.equals(updated.getCancelAtPeriodEnd()));
        current.setStatus(updated.getStatus());
        current.setCurrentPeriodStart(fromEpoch(updated.getCurrentPeriodStart()));
        current.setCurrentPeriodEnd(fromEpoch(updated.getCurrentPeriodEnd()));
        current.setLastSyncedAt(now);
    }

    private UserSubscriptionEntity getOrCreateUserSubscription(String clerkUserId) {
        UserSubscriptionEntity entity = findByUser(clerkUserId);
        if (entity != null) {
            return entity;
        }
        LocalDateTime now = LocalDateTime.now();
        userSubscriptionMapper.insertFreeIfAbsent(clerkUserId, now);
        UserSubscriptionEntity persisted =
                userSubscriptionMapper.selectByUserForUpdate(clerkUserId);
        if (persisted == null) {
            throw new IllegalStateException(
                    "Failed to create or load user subscription: " + clerkUserId);
        }
        return persisted;
    }

    private String ensureStripeCustomer(
            UserSubscriptionEntity userSubscription,
            String clerkUserId,
            String customerEmail) {
        if (userSubscription.getStripeCustomerId() != null
                && !userSubscription.getStripeCustomerId().isBlank()) {
            return userSubscription.getStripeCustomerId();
        }
        CustomerCreateParams.Builder builder = CustomerCreateParams.builder()
                .putMetadata("clerk_user_id", clerkUserId);
        if (customerEmail != null && !customerEmail.isBlank()) {
            builder.setEmail(customerEmail);
        }
        try {
            Customer customer = createStripeCustomer(builder.build());
            userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                    .eq(UserSubscriptionEntity::getId, userSubscription.getId())
                    .isNull(UserSubscriptionEntity::getStripeCustomerId)
                    .set(UserSubscriptionEntity::getStripeCustomerId, customer.getId())
                    .set(UserSubscriptionEntity::getUpdatedAt, LocalDateTime.now()));
            userSubscription.setStripeCustomerId(customer.getId());
            return customer.getId();
        } catch (StripeException e) {
            throw stripeFailure("Create Stripe customer failed", e);
        }
    }

    Session createStripeCheckoutSession(SessionCreateParams params) throws StripeException {
        return Session.create(params);
    }

    Session createStripeCheckoutSession(SessionCreateParams params, RequestOptions options) throws StripeException {
        return Session.create(params, options);
    }

    Customer createStripeCustomer(CustomerCreateParams params) throws StripeException {
        return Customer.create(params);
    }

    com.stripe.model.billingportal.Session createStripeBillingPortalSession(
            com.stripe.param.billingportal.SessionCreateParams params) throws StripeException {
        return com.stripe.model.billingportal.Session.create(params);
    }

    Invoice retrieveStripeInvoice(String invoiceId) throws StripeException {
        return Invoice.retrieve(invoiceId);
    }

    Charge retrieveStripeCharge(String chargeId) throws StripeException {
        return Charge.retrieve(chargeId);
    }

    Session retrieveStripeCheckoutSession(String sessionId) throws StripeException {
        return Session.retrieve(sessionId);
    }

    Session expireStripeCheckoutSession(Session session) throws StripeException {
        return session.expire();
    }

    Subscription retrieveStripeSubscription(String subscriptionId) throws StripeException {
        return Subscription.retrieve(subscriptionId);
    }

    Subscription updateStripeSubscription(
            Subscription subscription,
            SubscriptionUpdateParams params) throws StripeException {
        return subscription.update(params);
    }

    SubscriptionSchedule retrieveStripeSubscriptionSchedule(String scheduleId) throws StripeException {
        return SubscriptionSchedule.retrieve(scheduleId);
    }

    SubscriptionSchedule createStripeSubscriptionSchedule(
            SubscriptionScheduleCreateParams params,
            RequestOptions options) throws StripeException {
        return SubscriptionSchedule.create(params, options);
    }

    SubscriptionSchedule updateStripeSubscriptionSchedule(
            SubscriptionSchedule schedule,
            SubscriptionScheduleUpdateParams params,
            RequestOptions options) throws StripeException {
        return schedule.update(params, options);
    }

    SubscriptionSchedule releaseStripeSubscriptionSchedule(
            SubscriptionSchedule schedule,
            SubscriptionScheduleReleaseParams params) throws StripeException {
        return schedule.release(params);
    }

    boolean clearStoredStripeCustomer(UserSubscriptionEntity userSubscription) {
        if (userSubscription == null || !hasText(userSubscription.getStripeCustomerId())) {
            return false;
        }
        String oldCustomerId = userSubscription.getStripeCustomerId();
        int updated = userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, userSubscription.getId())
                .eq(UserSubscriptionEntity::getStripeCustomerId, oldCustomerId)
                .set(UserSubscriptionEntity::getStripeCustomerId, null)
                .set(UserSubscriptionEntity::getUpdatedAt, LocalDateTime.now()));
        if (updated == 1) {
            userSubscription.setStripeCustomerId(null);
            return true;
        }
        return false;
    }

    static boolean shouldRetrySubscriptionCheckoutWithFreshCustomer(
            UserSubscriptionEntity userSubscription,
            StripeException exception) {
        if (userSubscription == null || !hasText(userSubscription.getStripeCustomerId())) {
            return false;
        }
        String status = normalizeNullableText(userSubscription.getStatus());
        String tier = normalizeNullableText(userSubscription.getTier());
        boolean freeLikeState = userSubscription.getPlanCode() == null
                && ("free".equals(tier) || "free".equals(status) || "canceled".equals(status));
        boolean localMockSubscriptionState = hasText(userSubscription.getStripeSubscriptionId())
                && !isRealStripeReference(userSubscription.getStripeSubscriptionId());
        return (freeLikeState || localMockSubscriptionState) && isMissingStripeCustomer(exception);
    }

    static boolean isMissingStripeCustomer(StripeException exception) {
        if (!(exception instanceof InvalidRequestException)) {
            return false;
        }
        String message = normalizeNullableText(exception.getMessage());
        String code = normalizeNullableText(exception.getCode());
        return message.contains("no such customer")
                || ("resource_missing".equals(code) && message.contains("customer"))
                || message.contains("permanently deleted");
    }

    private SubscriptionPlanEntity requirePlan(String planCode) {
        return requirePlan(planCode, true);
    }

    private SubscriptionPlanEntity requirePlan(String planCode, boolean requireStripePrice) {
        String normalizedPlanCode = normalizePlanCode(planCode);
        SubscriptionPlanEntity plan = subscriptionPlanMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPlanEntity>()
                        .eq(SubscriptionPlanEntity::getPlanCode, normalizedPlanCode)
                        .eq(SubscriptionPlanEntity::getIsActive, true)
                        .last("LIMIT 1"));
        if (plan == null) {
            throw new BillingDomainException("INVALID_PLAN", "Unknown or inactive plan: " + normalizedPlanCode);
        }
        if (requireStripePrice
                && (plan.getStripePriceId() == null || !plan.getStripePriceId().startsWith("price_"))) {
            throw new BillingDomainException("PLAN_PRICE_NOT_CONFIGURED", "Stripe Price is not configured: " + normalizedPlanCode);
        }
        return plan;
    }

    private SubscriptionPlanEntity requireCurrentPlan(UserSubscriptionEntity current) {
        if (current == null) {
            throw new BillingDomainException("SUBSCRIPTION_NOT_FOUND", "Active subscription not found");
        }
        if (hasText(current.getPlanCode())) {
            // A retired plan can remain active on Stripe after new sales stop.
            return requireRuntimePlan(current.getPlanCode());
        }
        if (hasText(current.getPendingPlanCode())
                && ("active".equals(current.getStatus()) || "trialing".equals(current.getStatus()))) {
            SubscriptionPlanEntity repairedPlan = requirePlan(current.getPendingPlanCode());
            repairActivatedInitialSubscription(current, repairedPlan);
            return repairedPlan;
        }
        throw new BillingDomainException("SUBSCRIPTION_STATE_INVALID", "Current subscription plan is missing");
    }

    private void repairActivatedInitialSubscription(UserSubscriptionEntity current, SubscriptionPlanEntity plan) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime currentPeriodStart = current.getCurrentPeriodStart();
        LocalDateTime currentPeriodEnd = current.getCurrentPeriodEnd();
        Boolean cancelAtPeriodEnd = current.getCancelAtPeriodEnd();
        String status = current.getStatus();
        try {
            if (hasText(current.getStripeSubscriptionId())) {
                Subscription subscription = Subscription.retrieve(current.getStripeSubscriptionId());
                status = subscription.getStatus();
                cancelAtPeriodEnd = Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd());
                if (subscription.getCurrentPeriodStart() != null) {
                    currentPeriodStart = fromEpoch(subscription.getCurrentPeriodStart());
                }
                if (subscription.getCurrentPeriodEnd() != null) {
                    currentPeriodEnd = fromEpoch(subscription.getCurrentPeriodEnd());
                }
            }
        } catch (StripeException e) {
            throw stripeFailure("Repair subscription state failed", e);
        }

        LocalDateTime quotaPeriodEnd = currentPeriodEnd;
        if ("year".equals(plan.getBillingInterval()) && currentPeriodStart != null) {
            quotaPeriodEnd = currentPeriodStart.plusMonths(1);
        }

        userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, current.getId())
                .set(UserSubscriptionEntity::getTier, plan.getTier())
                .set(UserSubscriptionEntity::getPlanCode, plan.getPlanCode())
                .set(UserSubscriptionEntity::getStatus, status)
                .set(UserSubscriptionEntity::getCurrentPeriodStart, currentPeriodStart)
                .set(UserSubscriptionEntity::getCurrentPeriodEnd, currentPeriodEnd)
                .set(UserSubscriptionEntity::getQuotaPeriodStart, currentPeriodStart)
                .set(UserSubscriptionEntity::getQuotaPeriodEnd, quotaPeriodEnd)
                .set(UserSubscriptionEntity::getCancelAtPeriodEnd, Boolean.TRUE.equals(cancelAtPeriodEnd))
                .set(UserSubscriptionEntity::getPendingPlanCode, null)
                .set(UserSubscriptionEntity::getPendingEffectiveAt, null)
                .set(UserSubscriptionEntity::getLastSyncedAt, now)
                .set(UserSubscriptionEntity::getUpdatedAt, now));
        current.setTier(plan.getTier());
        current.setPlanCode(plan.getPlanCode());
        current.setStatus(status);
        current.setCurrentPeriodStart(currentPeriodStart);
        current.setCurrentPeriodEnd(currentPeriodEnd);
        current.setQuotaPeriodStart(currentPeriodStart);
        current.setQuotaPeriodEnd(quotaPeriodEnd);
        current.setCancelAtPeriodEnd(Boolean.TRUE.equals(cancelAtPeriodEnd));
        current.setPendingPlanCode(null);
        current.setPendingEffectiveAt(null);
        current.setLastSyncedAt(now);
        current.setUpdatedAt(now);
    }

    private String normalizePlanCode(String planCode) {
        if (planCode == null || planCode.isBlank()) {
            throw new BillingDomainException("INVALID_PLAN", "planCode is required");
        }
        return planCode.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeNullableText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeCurrency(String currency) {
        return hasText(currency) ? currency.toLowerCase(Locale.ROOT) : "usd";
    }

    private SubscriptionPlanEntity requireRuntimePlan(String planCode) {
        SubscriptionPlanEntity plan = subscriptionPlanMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPlanEntity>()
                        .eq(SubscriptionPlanEntity::getPlanCode, planCode)
                        .last("LIMIT 1"));
        if (plan == null) {
            throw new BillingDomainException("INVALID_PLAN", "Unknown plan: " + planCode);
        }
        return plan;
    }

    private AddonPackageDefEntity requireAddon(String addonCode) {
        AddonPackageDefEntity addon = addonPackageDefMapper.selectOne(
                new LambdaQueryWrapper<AddonPackageDefEntity>()
                        .eq(AddonPackageDefEntity::getAddonCode, addonCode)
                        .eq(AddonPackageDefEntity::getIsActive, true)
                        .last("LIMIT 1"));
        if (addon == null) {
            throw new BillingDomainException("INVALID_ADDON", "Unknown or inactive add-on: " + addonCode);
        }
        if (addon.getStripePriceId() == null || !addon.getStripePriceId().startsWith("price_")) {
            throw new BillingDomainException("ADDON_PRICE_NOT_CONFIGURED", "Stripe Price is not configured: " + addonCode);
        }
        if (addon.getValidityMonths() == null || addon.getValidityMonths() <= 0) {
            throw new BillingDomainException(
                    "INVALID_ADDON_SNAPSHOT",
                    "Add-on validity must be positive: " + addonCode);
        }
        return addon;
    }

    private UserSubscriptionEntity requireCurrentSubscription(String clerkUserId) {
        UserSubscriptionEntity entity = findByUser(clerkUserId);
        if (entity == null || entity.getStripeSubscriptionId() == null) {
            throw new BillingDomainException("SUBSCRIPTION_NOT_FOUND", "Active subscription not found");
        }
        return entity;
    }

    private UserSubscriptionEntity findByUser(String clerkUserId) {
        return userSubscriptionMapper.selectOne(
                new LambdaQueryWrapper<UserSubscriptionEntity>()
                        .eq(UserSubscriptionEntity::getClerkUserId, clerkUserId)
                        .last("LIMIT 1"));
    }

    private void insertPendingOrder(
            String clerkUserId,
            String orderType,
            String featureCode,
            String packageCode,
            String planCode,
            String addonCode,
            long quotaAmount,
            Integer validityMonthsSnapshot,
            int priceCents,
            String currency,
            String stripeSessionId,
            String paymentIntentId,
            String subscriptionId) {
        LocalDateTime now = LocalDateTime.now();
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setOrderNo(generateOrderNo());
        order.setOrderType(orderType);
        order.setClerkUserId(clerkUserId);
        order.setFeatureCode(featureCode);
        order.setPackageCode(packageCode);
        order.setPlanCode(planCode);
        order.setAddonCode(addonCode);
        order.setQuotaAmount(quotaAmount);
        order.setValidityMonthsSnapshot(validityMonthsSnapshot);
        order.setPriceCents(priceCents);
        order.setCurrency(currency == null ? "usd" : currency);
        order.setStripeSessionId(stripeSessionId);
        order.setStripePaymentIntentId(paymentIntentId);
        order.setStripeSubscriptionId(subscriptionId);
        order.setStatus("pending");
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        rechargeOrderMapper.insert(order);
    }

    private void insertCompletedMockSubscriptionOrder(
            String clerkUserId,
            SubscriptionPlanEntity plan,
            String stripeSessionId,
            String subscriptionId,
            LocalDateTime paidAt) {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setOrderNo(generateOrderNo());
        order.setOrderType("subscription_initial");
        order.setClerkUserId(clerkUserId);
        order.setFeatureCode("subscription");
        order.setPackageCode(plan.getPlanCode());
        order.setPlanCode(plan.getPlanCode());
        order.setQuotaAmount(0L);
        order.setPriceCents(plan.getPriceCents());
        order.setCurrency(normalizeCurrency(plan.getCurrency()));
        order.setStripeSessionId(stripeSessionId);
        order.setStripeSubscriptionId(subscriptionId);
        order.setStatus("completed");
        order.setPaidAt(paidAt);
        order.setCreatedAt(paidAt);
        order.setUpdatedAt(paidAt);
        rechargeOrderMapper.insert(order);
    }

    private void insertPendingUpgradeOrder(
            String orderNo,
            String clerkUserId,
            SubscriptionPlanEntity currentPlan,
            SubscriptionPlanEntity targetPlan,
            UserSubscriptionEntity current,
            UpgradeChargeQuote quote,
            Session session) {
        LocalDateTime now = LocalDateTime.now();
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setOrderNo(orderNo);
        order.setOrderType("subscription_upgrade_manual");
        order.setClerkUserId(clerkUserId);
        order.setFeatureCode("subscription");
        order.setPackageCode(targetPlan.getPlanCode());
        order.setPlanCode(currentPlan.getPlanCode());
        order.setTargetPlanCode(targetPlan.getPlanCode());
        order.setQuotaAmount(0L);
        order.setPriceCents(quote.getAmountCents());
        order.setQuotedAmountCents(quote.getAmountCents());
        order.setUpgradeChargeType(quote.getChargeType());
        order.setCurrency(normalizeCurrency(targetPlan.getCurrency()));
        order.setStripeSessionId(session.getId());
        order.setStripePaymentIntentId(session.getPaymentIntent());
        order.setStripeSubscriptionId(current.getStripeSubscriptionId());
        order.setStatus("checkout_created");
        order.setSwitchAttempts(0);
        order.setBizContext(GSON.toJson(Map.of(
                "current_tier", currentPlan.getTier(),
                "target_tier", targetPlan.getTier(),
                "current_interval", currentPlan.getBillingInterval(),
                "target_interval", targetPlan.getBillingInterval(),
                "remaining_annual_months_excluding_current", quote.getRemainingAnnualMonthsExcludingCurrent(),
                "pricing_formula", quote.getPricingFormula(),
                "source_invoice_id", String.valueOf(quote.getSourceInvoiceId()),
                "current_net_paid_cents", quote.getCurrentNetPaidCents(),
                "old_period_start", String.valueOf(current.getCurrentPeriodStart()),
                "old_period_end", String.valueOf(current.getCurrentPeriodEnd())
        )));
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        rechargeOrderMapper.insert(order);
    }

    private void markPendingUpgradeCheckout(UserSubscriptionEntity current, String orderNo, Session session) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = fromEpoch(session.getExpiresAt());
        userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, current.getId())
                .set(UserSubscriptionEntity::getPendingUpgradeOrderNo, orderNo)
                .set(UserSubscriptionEntity::getPendingUpgradeExpiresAt, expiresAt)
                .set(UserSubscriptionEntity::getUpdatedAt, now));
        current.setPendingUpgradeOrderNo(orderNo);
        current.setPendingUpgradeExpiresAt(expiresAt);
        current.setUpdatedAt(now);
    }

    static String buildManualUpgradeCheckoutIdempotencyKey(String orderNo) {
        return "manual-upgrade-checkout:" + orderNo;
    }

    SessionCreateParams buildManualUpgradeCheckoutParams(
            String customerId,
            String clerkUserId,
            SubscriptionPlanEntity currentPlan,
            SubscriptionPlanEntity targetPlan,
            UserSubscriptionEntity current,
            UpgradeChargeQuote quote,
            String orderNo,
            String requestedSuccessUrl,
            String requestedCancelUrl,
            String resumeToken,
            SessionCreateParams.PaymentIntentData paymentIntentData) {
        return SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCustomer(customerId)
                .setClientReferenceId(clerkUserId)
                .setSuccessUrl(resolveCheckoutSuccessUrl(requestedSuccessUrl, resumeToken))
                .setCancelUrl(resolveCheckoutCancelUrl(requestedCancelUrl))
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(normalizeCurrency(targetPlan.getCurrency()))
                                .setUnitAmount((long) quote.getAmountCents())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("Subscription upgrade to " + targetPlan.getPlanCode())
                                        .build())
                                .build())
                        .build())
                .setInvoiceCreation(SessionCreateParams.InvoiceCreation.builder()
                        .setEnabled(true)
                        .setInvoiceData(SessionCreateParams.InvoiceCreation.InvoiceData.builder()
                                .setDescription("Subscription upgrade charge from "
                                        + currentPlan.getPlanCode() + " to " + targetPlan.getPlanCode())
                                .putMetadata("purchase_type", "subscription_upgrade_manual")
                                .putMetadata("upgrade_order_no", orderNo)
                                .putMetadata("clerk_user_id", clerkUserId)
                                .putMetadata("current_plan_code", currentPlan.getPlanCode())
                                .putMetadata("target_plan_code", targetPlan.getPlanCode())
                                .putMetadata("current_subscription_id", current.getStripeSubscriptionId())
                                .build())
                        .build())
                .putMetadata("purchase_type", "subscription_upgrade_manual")
                .putMetadata("upgrade_order_no", orderNo)
                .putMetadata("clerk_user_id", clerkUserId)
                .putMetadata("current_plan_code", currentPlan.getPlanCode())
                .putMetadata("target_plan_code", targetPlan.getPlanCode())
                .putMetadata("current_subscription_id", current.getStripeSubscriptionId())
                .setPaymentIntentData(paymentIntentData)
                .build();
    }

    private BillingPlan toPlan(SubscriptionPlanEntity entity) {
        String offerKind = hasText(entity.getOfferKind())
                ? entity.getOfferKind()
                : (IntroTrialPlans.isIntroTrialPlanCode(entity.getPlanCode())
                ? IntroTrialPlans.OFFER_KIND_BASIC_PAID_TRIAL
                : IntroTrialPlans.OFFER_KIND_STANDARD);
        return BillingPlan.builder()
                .planCode(entity.getPlanCode())
                .tier(entity.getTier())
                .offerKind(offerKind)
                .billingInterval(entity.getBillingInterval())
                .trialDays(entity.getTrialDays())
                .convertsToPlanCode(entity.getConvertsToPlanCode())
                .stripeProductId(entity.getStripeProductId())
                .stripePriceId(entity.getStripePriceId())
                .priceCents(entity.getPriceCents())
                .currency(entity.getCurrency())
                .assignmentQuota(entity.getAssignmentQuota())
                .assignmentQuotaUnit("time")
                .detectionQuota(entity.getDetectionQuota())
                .detectionQuotaUnit("words")
                .humanizerQuota(entity.getHumanizerQuota())
                .humanizerQuotaUnit("words")
                .maxFiles(entity.getMaxFiles())
                .maxFollowupEdits(entity.getMaxFollowupEdits())
                .allowedOutputTypes(entity.getAllowedOutputTypes())
                .build();
    }

    private BillingAddon toAddon(AddonPackageDefEntity entity) {
        return BillingAddon.builder()
                .addonCode(entity.getAddonCode())
                .featureCode(entity.getFeatureCode())
                .stripeProductId(entity.getStripeProductId())
                .stripePriceId(entity.getStripePriceId())
                .quotaAmount(entity.getQuotaAmount())
                .quotaUnit(resolveQuotaUnit(entity.getFeatureCode()))
                .validityMonths(entity.getValidityMonths())
                .priceCents(entity.getPriceCents())
                .currency(entity.getCurrency())
                .build();
    }

    private String resolveQuotaUnit(String featureCode) {
        if ("ai_detection".equals(featureCode) || "humanizer".equals(featureCode)) {
            return "words";
        }
        return "time";
    }

    private SubscriptionResult toResult(UserSubscriptionEntity entity) {
        String status = entity.getStatus();
        String tier = entity.getTier();
        // Frontend unpaid contract uses free (not lapsed), even after Free hard-cut.
        if ("canceled".equalsIgnoreCase(status)
                || "free".equalsIgnoreCase(status)
                || "lapsed".equalsIgnoreCase(status)) {
            status = "free";
            tier = "free";
        }
        BillingAccessState accessState = BillingEntitlementPolicy.resolveAccessState(
                status,
                Boolean.TRUE.equals(entity.getCancelAtPeriodEnd()),
                entity.getGraceEndAt(),
                LocalDateTime.now());
        boolean canConsume = BillingEntitlementPolicy.allowsPaidEntitlementConsumption(
                status, entity.getGraceEndAt(), LocalDateTime.now());
        boolean canRefresh = BillingEntitlementPolicy.allowsPlanRefresh(status);
        boolean canPurchaseAddon = BillingEntitlementPolicy.allowsAddonPurchase(status);
        boolean introTrialUsed = entity.getIntroTrialUsedAt() != null;
        boolean onIntroTrial = IntroTrialPlans.isIntroTrialPlanCode(entity.getPlanCode())
                || IntroTrialPlans.PHASE_INTRO.equalsIgnoreCase(entity.getSubscriptionPhase());
        BasicTrialAccount basicTrial = resolveBasicTrialAccount(entity, canConsume, onIntroTrial, introTrialUsed);
        return SubscriptionResult.builder()
                .tier(tier)
                .planCode(entity.getPlanCode())
                .status(status)
                .accessState(accessState)
                .canConsumePaidEntitlements(canConsume)
                .canRefreshPlan(canRefresh)
                .canPurchaseAddon(canPurchaseAddon)
                .availableActions(resolveAvailableActions(accessState, canPurchaseAddon, introTrialUsed))
                .stripeCustomerId(entity.getStripeCustomerId())
                .stripeSubscriptionId(entity.getStripeSubscriptionId())
                .stripeScheduleId(entity.getStripeScheduleId())
                .currentPeriodStart(entity.getCurrentPeriodStart())
                .currentPeriodEnd(entity.getCurrentPeriodEnd())
                .quotaPeriodStart(entity.getQuotaPeriodStart())
                .quotaPeriodEnd(entity.getQuotaPeriodEnd())
                .cancelAtPeriodEnd(Boolean.TRUE.equals(entity.getCancelAtPeriodEnd()))
                .pendingPlanCode(entity.getPendingPlanCode())
                .pendingEffectiveAt(entity.getPendingEffectiveAt())
                .basicTrial(basicTrial)
                .build();
    }

    private BasicTrialAccount resolveBasicTrialAccount(
            UserSubscriptionEntity entity,
            boolean canConsume,
            boolean onIntroTrial,
            boolean introTrialUsed) {
        String convertsToPlanCode = null;
        String convertsToBillingInterval = null;
        if (onIntroTrial) {
            convertsToPlanCode = firstNonBlank(
                    entity.getPendingPlanCode(),
                    resolveConversionPlanCodeForUser(entity));
            convertsToBillingInterval = IntroTrialPlans.conversionBillingInterval(convertsToPlanCode);
            if (convertsToBillingInterval == null && hasText(convertsToPlanCode)) {
                SubscriptionPlanEntity conversion = subscriptionPlanMapper.selectOne(
                        new LambdaQueryWrapper<SubscriptionPlanEntity>()
                                .eq(SubscriptionPlanEntity::getPlanCode, convertsToPlanCode)
                                .last("LIMIT 1"));
                if (conversion != null) {
                    convertsToBillingInterval = conversion.getBillingInterval();
                }
            }
        }

        String eligibility;
        if (onIntroTrial && canConsume) {
            eligibility = IntroTrialPlans.ELIGIBILITY_ACTIVE_TRIAL;
        } else if (canConsume) {
            eligibility = IntroTrialPlans.ELIGIBILITY_ACTIVE_SUBSCRIPTION;
        } else if (introTrialUsed) {
            eligibility = IntroTrialPlans.ELIGIBILITY_USED;
        } else if (!introTrialEnabled) {
            eligibility = IntroTrialPlans.ELIGIBILITY_UNKNOWN;
        } else {
            eligibility = IntroTrialPlans.ELIGIBILITY_ELIGIBLE;
        }

        return BasicTrialAccount.builder()
                .eligibility(eligibility)
                .active(onIntroTrial && canConsume)
                .used(introTrialUsed)
                .eligible(IntroTrialPlans.ELIGIBILITY_ELIGIBLE.equals(eligibility))
                .endsAt(onIntroTrial && canConsume ? entity.getCurrentPeriodEnd() : null)
                .convertsToPlanCode(convertsToPlanCode)
                .convertsToBillingInterval(convertsToBillingInterval)
                .build();
    }

    private SubscriptionResult freeSubscription() {
        boolean hardCut = introTrialEnabled;
        return SubscriptionResult.builder()
                .tier("free")
                .status("free")
                .accessState(BillingAccessState.TERMINATED)
                .canConsumePaidEntitlements(false)
                .canRefreshPlan(false)
                .canPurchaseAddon(false)
                .availableActions(hardCut
                        ? List.of("subscribe", "start_intro_trial")
                        : List.of("subscribe"))
                .isAdmin(false)
                .isQuotaVip(false)
                .effectiveMaxFiles(hardCut ? 0 : 3)
                .effectiveMaxFollowupEdits(hardCut ? 0 : 3)
                .effectiveAllowedOutputTypes(List.of("writing"))
                .cancelAtPeriodEnd(false)
                .basicTrial(BasicTrialAccount.builder()
                        .eligibility(hardCut
                                ? IntroTrialPlans.ELIGIBILITY_ELIGIBLE
                                : IntroTrialPlans.ELIGIBILITY_UNKNOWN)
                        .active(false)
                        .used(false)
                        .eligible(hardCut)
                        .build())
                .build();
    }

    private List<String> resolveAvailableActions(
            BillingAccessState accessState,
            boolean canPurchaseAddon) {
        return resolveAvailableActions(accessState, canPurchaseAddon, false);
    }

    private List<String> resolveAvailableActions(
            BillingAccessState accessState,
            boolean canPurchaseAddon,
            boolean introTrialUsed) {
        java.util.ArrayList<String> actions = new java.util.ArrayList<>();
        if (canPurchaseAddon) {
            actions.add("purchase_addon");
        }
        switch (accessState) {
            case ACTIVE -> actions.addAll(List.of("change_plan", "cancel_subscription"));
            case ACTIVE_ENDING -> actions.add("resume_subscription");
            case GRACE, PAYMENT_PENDING, SUSPENDED -> actions.add("resolve_payment");
            case TERMINATED -> {
                actions.add("subscribe");
                if (!introTrialUsed && introTrialEnabled) {
                    actions.add("start_intro_trial");
                }
            }
        }
        return List.copyOf(actions);
    }

    private List<String> parseAllowedOutputTypes(String rawAllowedOutputTypes) {
        if (rawAllowedOutputTypes == null || rawAllowedOutputTypes.isBlank()) {
            return List.of("writing");
        }
        String[] parsed = GSON.fromJson(rawAllowedOutputTypes, String[].class);
        if (parsed == null || parsed.length == 0) {
            return List.of("writing");
        }
        return List.of(parsed);
    }

    private String withSessionId(String url) {
        if (url.contains("{CHECKOUT_SESSION_ID}")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "session_id={CHECKOUT_SESSION_ID}";
    }

    String resolveCheckoutSuccessUrl(String requestedUrl, String resumeToken) {
        String resolved = resolveCheckoutReturnUrl(requestedUrl, successUrl);
        String withResumeToken = appendQueryParam(resolved, "resumeToken", resumeToken);
        return withSessionId(withResumeToken);
    }

    String resolveMockCheckoutSuccessUrl(String successUrl, String sessionId) {
        return successUrl.replace("{CHECKOUT_SESSION_ID}", sessionId);
    }

    String resolveUpgradeSuccessUrl(String requestedUrl, String resumeToken) {
        String resolved = resolveCheckoutReturnUrl(requestedUrl, successUrl);
        return appendQueryParam(resolved, "resumeToken", resumeToken);
    }

    String resolveCheckoutCancelUrl(String requestedUrl) {
        return resolveCheckoutReturnUrl(requestedUrl, cancelUrl);
    }

    String resolveBillingPortalReturnUrl(String requestedUrl) {
        if (!hasText(requestedUrl)) {
            throw new BillingDomainException("INVALID_RETURN_URL", "returnUrl is required");
        }
        return resolveCheckoutReturnUrl(requestedUrl, successUrl);
    }

    String resolveMockBillingPortalUrl(String returnUrl, String stripeCustomerId) {
        String configured = billingPortalMockUrl == null ? "" : billingPortalMockUrl.trim();
        if ("return-url".equalsIgnoreCase(configured)) {
            if (returnUrl.contains("mockBillingPortal=stripe")) {
                return returnUrl;
            }
            return appendQueryParam(returnUrl, "mockBillingPortal", "stripe");
        }
        String withReturn = appendEncodedQueryParam(configured, "returnUrl", returnUrl);
        return appendEncodedQueryParam(withReturn, "customer", stripeCustomerId);
    }

    private String appendQueryParam(String url, String key, String value) {
        if (!hasText(value)) {
            return url;
        }
        int fragmentIndex = url.indexOf('#');
        String fragment = fragmentIndex >= 0 ? url.substring(fragmentIndex) : "";
        String base = fragmentIndex >= 0 ? url.substring(0, fragmentIndex) : url;
        return base + (base.contains("?") ? "&" : "?") + key + "=" + value + fragment;
    }

    private String appendEncodedQueryParam(String url, String key, String value) {
        if (!hasText(value)) {
            return url;
        }
        return appendQueryParam(url, key, URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private String resolveCheckoutReturnUrl(String requestedUrl, String fallbackUrl) {
        if (requestedUrl == null || requestedUrl.isBlank()) {
            return fallbackUrl;
        }
        String trimmed = requestedUrl.trim();
        URI uri = parseCheckoutReturnUri(trimmed);
        if (!isAllowedCheckoutReturnUri(uri)) {
            throw new BillingDomainException("INVALID_RETURN_URL", trimmed);
        }
        return trimmed;
    }

    private URI parseCheckoutReturnUri(String url) {
        try {
            return new URI(url.replace("{CHECKOUT_SESSION_ID}", "CHECKOUT_SESSION_ID"));
        } catch (URISyntaxException e) {
            throw new BillingDomainException("INVALID_RETURN_URL", url, e);
        }
    }

    private boolean isAllowedCheckoutReturnUri(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return false;
        }

        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!"https".equals(normalizedScheme) && !"http".equals(normalizedScheme)) {
            return false;
        }

        return isLocalCheckoutHost(normalizedHost)
                || isVerlaCheckoutHost(normalizedHost)
                || sameOrigin(uri, successUrl)
                || sameOrigin(uri, cancelUrl);
    }

    private boolean isLocalCheckoutHost(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private boolean isVerlaCheckoutHost(String host) {
        return "verla.io".equals(host) || host.endsWith(".verla.io");
    }

    private boolean sameOrigin(URI candidate, String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            return false;
        }
        URI configured = parseCheckoutReturnUri(configuredUrl);
        return originOf(candidate).equals(originOf(configured));
    }

    private String originOf(URI uri) {
        int port = uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT)
                + "://"
                + uri.getHost().toLowerCase(Locale.ROOT)
                + (port >= 0 ? ":" + port : "");
    }

    private void requireStripeConfigured() {
        if (!isStripeConfigured()) {
            throw new BillingDomainException("STRIPE_NOT_CONFIGURED", "Stripe Secret Key is not configured");
        }
    }

    private boolean isStripeConfigured() {
        return stripeSecretKey != null && !stripeSecretKey.isBlank() && !stripeSecretKey.equals("sk_test_xxx");
    }

    private BillingDomainException stripeFailure(String message, StripeException cause) {
        return new BillingDomainException("STRIPE_ERROR", message + ": " + cause.getMessage(), cause);
    }

    private LocalDateTime fromEpoch(Long value) {
        return value == null ? null : LocalDateTime.ofInstant(Instant.ofEpochSecond(value), ZoneOffset.UTC);
    }

    private int tierRank(String tier) {
        return tierRankStatic(tier);
    }

    /**
     * Plan-code-aware change classifier. Trial SKUs share {@code tier=basic} with
     * standard Basic, so callers must pass plan codes to avoid false NOOP.
     */
    static PlanChangeAction classifyPlanChange(
            String currentPlanCode,
            String currentTier,
            String currentInterval,
            String targetPlanCode,
            String targetTier,
            String targetInterval) {
        if (currentTier == null || currentInterval == null || targetTier == null || targetInterval == null) {
            return PlanChangeAction.UNSUPPORTED;
        }
        if (currentPlanCode != null
                && targetPlanCode != null
                && currentPlanCode.equalsIgnoreCase(targetPlanCode)) {
            return PlanChangeAction.NOOP;
        }
        boolean currentTrial = IntroTrialPlans.isIntroTrialPlanCode(currentPlanCode);
        boolean targetTrial = IntroTrialPlans.isIntroTrialPlanCode(targetPlanCode);
        // Cannot change into Basic trial from any existing subscription.
        if (targetTrial) {
            return PlanChangeAction.UNSUPPORTED;
        }
        // Trial → Basic is automatic via Schedule; Trial → Plus/Pro is immediate upgrade.
        if (currentTrial) {
            if (IntroTrialPlans.isBasicPaidTier(targetTier)) {
                return PlanChangeAction.UNSUPPORTED;
            }
            return tierRankStatic(targetTier) > tierRankStatic("basic")
                    ? PlanChangeAction.IMMEDIATE_UPGRADE
                    : PlanChangeAction.UNSUPPORTED;
        }
        if (currentTier.equals(targetTier) && currentInterval.equals(targetInterval)) {
            return PlanChangeAction.NOOP;
        }
        if (isAnnualToMonthlySwitch(currentInterval, targetInterval)) {
            return PlanChangeAction.UNSUPPORTED;
        }
        if (isSameTierIntervalSwitch(currentTier, targetTier)) {
            return isMonthlyToAnnualSwitch(currentInterval, targetInterval)
                    ? PlanChangeAction.DEFERRED_CHANGE
                    : PlanChangeAction.UNSUPPORTED;
        }
        return tierRankStatic(targetTier) > tierRankStatic(currentTier)
                ? PlanChangeAction.IMMEDIATE_UPGRADE
                : PlanChangeAction.DEFERRED_CHANGE;
    }

    /** @deprecated prefer plan-code-aware overload */
    static PlanChangeAction classifyPlanChange(
            String currentTier,
            String currentInterval,
            String targetTier,
            String targetInterval) {
        return classifyPlanChange(null, currentTier, currentInterval, null, targetTier, targetInterval);
    }

    private static boolean isSameTierIntervalSwitch(String currentTier, String targetTier) {
        return currentTier.equals(targetTier);
    }

    private static boolean isAnnualToMonthlySwitch(String currentInterval, String targetInterval) {
        return "year".equals(currentInterval) && "month".equals(targetInterval);
    }

    private static boolean isMonthlyToAnnualSwitch(String currentInterval, String targetInterval) {
        return "month".equals(currentInterval) && "year".equals(targetInterval);
    }

    private static int tierRankStatic(String tier) {
        if (tier == null) {
            return 0;
        }
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "basic", "basic_trial" -> 1;
            case "plus" -> 2;
            case "pro" -> 3;
            default -> 0;
        };
    }

    private String generateOrderNo() {
        return "RO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}
