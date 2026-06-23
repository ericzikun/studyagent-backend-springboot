package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.Gson;
import com.stripe.Stripe;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionSchedule;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.SubscriptionScheduleCreateParams;
import com.stripe.param.SubscriptionScheduleReleaseParams;
import com.stripe.param.SubscriptionScheduleUpdateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.billing.BillingAddon;
import com.studyagent.service.domain.billing.BillingCatalogResult;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.BillingPlan;
import com.studyagent.service.domain.billing.SubscriptionResult;
import com.studyagent.service.domain.payment.CheckoutSessionResult;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingDomainServiceImpl implements BillingDomainService {
    private static final Set<String> BLOCKING_SUBSCRIPTION_STATUSES = Set.of(
            "active", "trialing", "past_due", "unpaid", "incomplete", "paused"
    );
    private static final Gson GSON = new Gson();

    enum PlanChangeAction {
        NOOP,
        IMMEDIATE_UPGRADE,
        DEFERRED_CHANGE,
        UNSUPPORTED
    }

    private final SubscriptionPlanMapper subscriptionPlanMapper;
    private final AddonPackageDefMapper addonPackageDefMapper;
    private final UserSubscriptionMapper userSubscriptionMapper;
    private final RechargeOrderMapper rechargeOrderMapper;

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    @Value("${payment.success-url:http://localhost:3000/payment-success}")
    private String successUrl;

    @Value("${payment.cancel-url:http://localhost:3000/payment-canceled}")
    private String cancelUrl;

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
        requireStripeConfigured();
        SubscriptionPlanEntity plan = requirePlan(planCode);
        UserSubscriptionEntity userSubscription = getOrCreateUserSubscription(clerkUserId);
        if (BLOCKING_SUBSCRIPTION_STATUSES.contains(userSubscription.getStatus())
                && userSubscription.getStripeSubscriptionId() != null) {
            return createManualUpgradeCheckout(
                    clerkUserId,
                    customerEmail,
                    plan,
                    userSubscription,
                    requestedSuccessUrl,
                    requestedCancelUrl,
                    resumeToken);
        }

        String customerId = ensureStripeCustomer(userSubscription, clerkUserId, customerEmail);
        userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, userSubscription.getId())
                .set(UserSubscriptionEntity::getPendingPlanCode, planCode)
                .set(UserSubscriptionEntity::getPendingEffectiveAt, null)
                .set(UserSubscriptionEntity::getUpdatedAt, LocalDateTime.now()));
        String finalSuccessUrl = resolveCheckoutSuccessUrl(requestedSuccessUrl, resumeToken);
        String finalCancelUrl = resolveCheckoutCancelUrl(requestedCancelUrl);
        SessionCreateParams.SubscriptionData subscriptionData = SessionCreateParams.SubscriptionData.builder()
                .putMetadata("purchase_type", "subscription")
                .putMetadata("clerk_user_id", clerkUserId)
                .putMetadata("plan_code", planCode)
                .build();
        SessionCreateParams params = buildSubscriptionCheckoutParams(
                clerkUserId,
                customerId,
                planCode,
                plan,
                finalSuccessUrl,
                finalCancelUrl,
                subscriptionData);

        try {
            return createInitialSubscriptionCheckout(
                    clerkUserId,
                    planCode,
                    plan,
                    resumeToken,
                    params);
        } catch (StripeException e) {
            if (shouldRetrySubscriptionCheckoutWithFreshCustomer(userSubscription, e)) {
                UserSubscriptionEntity retrySubscription = userSubscription;
                if (!clearStoredStripeCustomer(userSubscription)) {
                    retrySubscription = getOrCreateUserSubscription(clerkUserId);
                }
                String retriedCustomerId = ensureStripeCustomer(retrySubscription, clerkUserId, customerEmail);
                SessionCreateParams retriedParams = buildSubscriptionCheckoutParams(
                        clerkUserId,
                        retriedCustomerId,
                        planCode,
                        plan,
                        finalSuccessUrl,
                        finalCancelUrl,
                        subscriptionData);
                try {
                    return createInitialSubscriptionCheckout(
                            clerkUserId,
                            planCode,
                            plan,
                            resumeToken,
                            retriedParams);
                } catch (StripeException retryException) {
                    throw stripeFailure("Create subscription Checkout failed", retryException);
                }
            }
            throw stripeFailure("Create subscription Checkout failed", e);
        }
    }

    private SessionCreateParams buildSubscriptionCheckoutParams(
            String clerkUserId,
            String customerId,
            String planCode,
            SubscriptionPlanEntity plan,
            String finalSuccessUrl,
            String finalCancelUrl,
            SessionCreateParams.SubscriptionData subscriptionData) {
        return SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .setClientReferenceId(clerkUserId)
                .setSuccessUrl(finalSuccessUrl)
                .setCancelUrl(finalCancelUrl)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(plan.getStripePriceId())
                        .setQuantity(1L)
                        .build())
                .putMetadata("purchase_type", "subscription")
                .putMetadata("clerk_user_id", clerkUserId)
                .putMetadata("plan_code", planCode)
                .setSubscriptionData(subscriptionData)
                .build();
    }

    private CheckoutSessionResult createInitialSubscriptionCheckout(
            String clerkUserId,
            String planCode,
            SubscriptionPlanEntity plan,
            String resumeToken,
            SessionCreateParams params) throws StripeException {
        Session session = createStripeCheckoutSession(params);
        insertPendingOrder(
                clerkUserId,
                "subscription_initial",
                "subscription",
                planCode,
                planCode,
                null,
                0L,
                plan.getPriceCents(),
                plan.getCurrency(),
                session.getId(),
                null,
                session.getSubscription());
        return CheckoutSessionResult.builder()
                .checkoutKind("session")
                .sessionId(session.getId())
                .referenceId(session.getId())
                .checkoutUrl(session.getUrl())
                .expiresAt(session.getExpiresAt())
                .resumeToken(resumeToken)
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

        SessionCreateParams.PaymentIntentData paymentIntentData = SessionCreateParams.PaymentIntentData.builder()
                .putMetadata("purchase_type", "addon")
                .putMetadata("clerk_user_id", clerkUserId)
                .putMetadata("addon_code", addonCode)
                .build();
        SessionCreateParams params = SessionCreateParams.builder()
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
                .build();
        try {
            Session session = Session.create(params);
            insertPendingOrder(
                    clerkUserId,
                    "addon",
                    addon.getFeatureCode(),
                    addonCode,
                    null,
                    addonCode,
                    addon.getQuotaAmount(),
                    addon.getPriceCents(),
                    addon.getCurrency(),
                    session.getId(),
                    session.getPaymentIntent(),
                    userSubscription.getStripeSubscriptionId());
            return CheckoutSessionResult.builder()
                    .checkoutKind("session")
                    .sessionId(session.getId())
                    .referenceId(session.getId())
                    .checkoutUrl(session.getUrl())
                    .expiresAt(session.getExpiresAt())
                    .resumeToken(resumeToken)
                    .build();
        } catch (StripeException e) {
            throw stripeFailure("Create add-on Checkout failed", e);
        }
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
                currentPlan.getTier(),
                currentPlan.getBillingInterval(),
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
        UpgradeChargeQuote quote = UpgradeChargeCalculator.quote(
                currentPlan,
                targetPlan,
                current.getQuotaPeriodStart() != null ? current.getQuotaPeriodStart() : current.getCurrentPeriodStart(),
                current.getCurrentPeriodEnd(),
                LocalDateTime.now());
        String orderNo = generateOrderNo();

        try {
            SessionCreateParams.PaymentIntentData paymentIntentData = SessionCreateParams.PaymentIntentData.builder()
                    .putMetadata("purchase_type", "subscription_upgrade_manual")
                    .putMetadata("upgrade_order_no", orderNo)
                    .putMetadata("clerk_user_id", clerkUserId)
                    .putMetadata("current_plan_code", currentPlan.getPlanCode())
                    .putMetadata("target_plan_code", targetPlan.getPlanCode())
                    .putMetadata("current_subscription_id", current.getStripeSubscriptionId())
                    .build();
            SessionCreateParams params = SessionCreateParams.builder()
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
                    .putMetadata("purchase_type", "subscription_upgrade_manual")
                    .putMetadata("upgrade_order_no", orderNo)
                    .putMetadata("clerk_user_id", clerkUserId)
                    .putMetadata("current_plan_code", currentPlan.getPlanCode())
                    .putMetadata("target_plan_code", targetPlan.getPlanCode())
                    .putMetadata("current_subscription_id", current.getStripeSubscriptionId())
                    .setPaymentIntentData(paymentIntentData)
                    .build();
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(buildManualUpgradeCheckoutIdempotencyKey(orderNo))
                    .build();
            Session session = Session.create(params, options);
            insertPendingUpgradeOrder(orderNo, clerkUserId, currentPlan, targetPlan, current, quote, session);
            markPendingUpgradeCheckout(current, orderNo, session);

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

    void clearPendingUpgradeStateForRetry(UserSubscriptionEntity current) {
        if (current == null || !hasText(current.getClerkUserId())) {
            return;
        }
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

    @Override
    public SubscriptionResult getCurrentSubscription(String clerkUserId) {
        UserSubscriptionEntity entity = findByUser(clerkUserId);
        return entity == null ? freeSubscription() : toResult(entity);
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
                currentPlan.getTier(),
                currentPlan.getBillingInterval(),
                targetPlan.getTier(),
                targetPlan.getBillingInterval())) {
            case NOOP -> toResult(current);
            case IMMEDIATE_UPGRADE -> upgradeSubscription(clerkUserId, normalizedTargetPlanCode);
            case DEFERRED_CHANGE -> downgradeSubscription(clerkUserId, normalizedTargetPlanCode);
            case UNSUPPORTED -> throw new BillingDomainException(
                    "SUBSCRIPTION_STATE_INVALID",
                    "This plan change is not supported");
        };
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubscriptionResult upgradeSubscription(String clerkUserId, String targetPlanCode) {
        requireStripeConfigured();
        String normalizedTargetPlanCode = normalizePlanCode(targetPlanCode);
        SubscriptionPlanEntity targetPlan = requirePlan(normalizedTargetPlanCode);
        UserSubscriptionEntity current = requireCurrentSubscription(clerkUserId);
        if (normalizedTargetPlanCode.equals(current.getPlanCode())) {
            return toResult(current);
        }
        SubscriptionPlanEntity currentPlan = requireCurrentPlan(current);
        PlanChangeAction action = classifyPlanChange(
                currentPlan.getTier(),
                currentPlan.getBillingInterval(),
                targetPlan.getTier(),
                targetPlan.getBillingInterval());
        if (action == PlanChangeAction.UNSUPPORTED) {
            throw new BillingDomainException("SUBSCRIPTION_STATE_INVALID", "This plan change is not supported");
        }
        if (action != PlanChangeAction.IMMEDIATE_UPGRADE) {
            throw new BillingDomainException("INVALID_UPGRADE_TARGET", "Target plan is not an upgrade");
        }

        try {
            Subscription stripeSubscription = Subscription.retrieve(current.getStripeSubscriptionId());
            if (stripeSubscription.getItems() == null
                    || stripeSubscription.getItems().getData() == null
                    || stripeSubscription.getItems().getData().size() != 1) {
                throw new BillingDomainException("INVALID_SUBSCRIPTION_ITEMS", "Subscription must contain one item");
            }
            SubscriptionItem item = stripeSubscription.getItems().getData().get(0);
            releasePendingScheduleIfPresent(current, stripeSubscription);
            SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
                    .setBillingCycleAnchor(SubscriptionUpdateParams.BillingCycleAnchor.NOW)
                    .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE)
                    .setPaymentBehavior(SubscriptionUpdateParams.PaymentBehavior.PENDING_IF_INCOMPLETE)
                    .addItem(SubscriptionUpdateParams.Item.builder()
                            .setId(item.getId())
                            .setPrice(targetPlan.getStripePriceId())
                            .build())
                    .putMetadata("clerk_user_id", clerkUserId)
                    .putMetadata("pending_plan_code", normalizedTargetPlanCode)
                    .build();
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey("upgrade:" + clerkUserId + ":" + current.getStripeSubscriptionId()
                            + ":" + normalizedTargetPlanCode + ":" + current.getCurrentPeriodEnd())
                    .build();
            stripeSubscription.update(params, options);

            userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                    .eq(UserSubscriptionEntity::getId, current.getId())
                    .set(UserSubscriptionEntity::getStripeScheduleId, null)
                    .set(UserSubscriptionEntity::getPendingPlanCode, normalizedTargetPlanCode)
                    .set(UserSubscriptionEntity::getPendingEffectiveAt, null)
                    .set(UserSubscriptionEntity::getUpdatedAt, LocalDateTime.now()));
            current.setStripeScheduleId(null);
            current.setPendingPlanCode(normalizedTargetPlanCode);
            current.setPendingEffectiveAt(null);
            insertPendingOrder(
                    clerkUserId,
                    "subscription_upgrade",
                    "subscription",
                    normalizedTargetPlanCode,
                    normalizedTargetPlanCode,
                    null,
                    0L,
                    targetPlan.getPriceCents(),
                    targetPlan.getCurrency(),
                    null,
                    null,
                    current.getStripeSubscriptionId());
            return toResult(current);
        } catch (StripeException e) {
            throw stripeFailure("Upgrade subscription failed", e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
                currentPlan.getTier(),
                currentPlan.getBillingInterval(),
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
            Subscription stripeSubscription = Subscription.retrieve(current.getStripeSubscriptionId());
            if (stripeSubscription.getItems() == null
                    || stripeSubscription.getItems().getData() == null
                    || stripeSubscription.getItems().getData().size() != 1) {
                throw new BillingDomainException("INVALID_SUBSCRIPTION_ITEMS", "Subscription must contain one item");
            }
            SubscriptionItem item = stripeSubscription.getItems().getData().get(0);
            if (item.getPrice() == null || item.getPrice().getId() == null) {
                throw new BillingDomainException("INVALID_SUBSCRIPTION_ITEMS", "Subscription item price is missing");
            }

            SubscriptionSchedule schedule = upsertDowngradeSchedule(
                    clerkUserId,
                    current,
                    stripeSubscription,
                    item,
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
        return BillingPlan.freePlan();
    }

    @Override
    public boolean isPaidMember(String clerkUserId) {
        UserSubscriptionEntity entity = findByUser(clerkUserId);
        if (entity == null || entity.getStripeSubscriptionId() == null) {
            return false;
        }
        return "active".equals(entity.getStatus()) || "trialing".equals(entity.getStatus());
    }

    private SubscriptionResult setCancelAtPeriodEnd(String clerkUserId, boolean cancel) {
        requireStripeConfigured();
        UserSubscriptionEntity current = requireCurrentSubscription(clerkUserId);
        try {
            Subscription subscription = Subscription.retrieve(current.getStripeSubscriptionId());
            String scheduleId = firstNonBlank(current.getStripeScheduleId(), subscription.getSchedule());
            if (canSkipCancellationUpdate(current.getCancelAtPeriodEnd(), cancel, current.getStripeScheduleId(), subscription.getSchedule())) {
                return toResult(current);
            }
            if (scheduleId != null) {
                releasePendingScheduleIfPresent(current, subscription);
                clearPendingScheduleState(current);
                subscription = Subscription.retrieve(current.getStripeSubscriptionId());
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

    private SubscriptionSchedule upsertDowngradeSchedule(
            String clerkUserId,
            UserSubscriptionEntity current,
            Subscription stripeSubscription,
            SubscriptionItem item,
            SubscriptionPlanEntity targetPlan) throws StripeException {
        String scheduleId = firstNonBlank(current.getStripeScheduleId(), stripeSubscription.getSchedule());
        SubscriptionSchedule schedule = retrieveReusableSchedule(scheduleId);
        if (schedule == null) {
            RequestOptions createOptions = RequestOptions.builder()
                    .setIdempotencyKey("downgrade-schedule:create:" + stripeSubscription.getId()
                            + ":" + targetPlan.getPlanCode() + ":" + stripeSubscription.getCurrentPeriodEnd())
                    .build();
            schedule = SubscriptionSchedule.create(
                    buildDowngradeScheduleCreateParams(stripeSubscription.getId()),
                    createOptions);
        }

        Long currentPhaseStart = currentPhaseStart(schedule, stripeSubscription);
        Long currentPhaseEnd = stripeSubscription.getCurrentPeriodEnd();
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
        return schedule.update(updateParams, updateOptions);
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
        SubscriptionSchedule schedule = SubscriptionSchedule.retrieve(scheduleId);
        return switch (schedule.getStatus()) {
            case "active", "not_started" -> schedule;
            default -> null;
        };
    }

    private void releasePendingScheduleIfPresent(
            UserSubscriptionEntity current,
            Subscription stripeSubscription) throws StripeException {
        String scheduleId = firstNonBlank(current.getStripeScheduleId(), stripeSubscription.getSchedule());
        if (scheduleId == null) {
            return;
        }
        SubscriptionSchedule schedule = SubscriptionSchedule.retrieve(scheduleId);
        if ("active".equals(schedule.getStatus()) || "not_started".equals(schedule.getStatus())) {
            schedule.release(SubscriptionScheduleReleaseParams.builder()
                    .setPreserveCancelDate(false)
                    .build());
        }
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
        entity = new UserSubscriptionEntity();
        entity.setClerkUserId(clerkUserId);
        entity.setTier("free");
        entity.setStatus("free");
        entity.setCancelAtPeriodEnd(false);
        entity.setVersion(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        userSubscriptionMapper.insert(entity);
        return entity;
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

    Customer createStripeCustomer(CustomerCreateParams params) throws StripeException {
        return Customer.create(params);
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
        return freeLikeState && isMissingStripeCustomer(exception);
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
        String normalizedPlanCode = normalizePlanCode(planCode);
        SubscriptionPlanEntity plan = subscriptionPlanMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPlanEntity>()
                        .eq(SubscriptionPlanEntity::getPlanCode, normalizedPlanCode)
                        .eq(SubscriptionPlanEntity::getIsActive, true)
                        .last("LIMIT 1"));
        if (plan == null) {
            throw new BillingDomainException("INVALID_PLAN", "Unknown or inactive plan: " + normalizedPlanCode);
        }
        if (plan.getStripePriceId() == null || !plan.getStripePriceId().startsWith("price_")) {
            throw new BillingDomainException("PLAN_PRICE_NOT_CONFIGURED", "Stripe Price is not configured: " + normalizedPlanCode);
        }
        return plan;
    }

    private SubscriptionPlanEntity requireCurrentPlan(UserSubscriptionEntity current) {
        if (current == null) {
            throw new BillingDomainException("SUBSCRIPTION_NOT_FOUND", "Active subscription not found");
        }
        if (hasText(current.getPlanCode())) {
            return requirePlan(current.getPlanCode());
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

    private BillingPlan toPlan(SubscriptionPlanEntity entity) {
        return BillingPlan.builder()
                .planCode(entity.getPlanCode())
                .tier(entity.getTier())
                .billingInterval(entity.getBillingInterval())
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
        if ("canceled".equalsIgnoreCase(status)) {
            status = "free";
        }
        return SubscriptionResult.builder()
                .tier(entity.getTier())
                .planCode(entity.getPlanCode())
                .status(status)
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
                .build();
    }

    private SubscriptionResult freeSubscription() {
        return SubscriptionResult.builder()
                .tier("free")
                .status("free")
                .cancelAtPeriodEnd(false)
                .build();
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

    String resolveUpgradeSuccessUrl(String requestedUrl, String resumeToken) {
        String resolved = resolveCheckoutReturnUrl(requestedUrl, successUrl);
        return appendQueryParam(resolved, "resumeToken", resumeToken);
    }

    String resolveCheckoutCancelUrl(String requestedUrl) {
        return resolveCheckoutReturnUrl(requestedUrl, cancelUrl);
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

    static SubscriptionUpdateParams buildSubscriptionUpgradeParams(
            String clerkUserId,
            SubscriptionPlanEntity targetPlan,
            SubscriptionItem item) {
        Long quantity = item.getQuantity() == null ? 1L : item.getQuantity();
        return SubscriptionUpdateParams.builder()
                .setBillingCycleAnchor(SubscriptionUpdateParams.BillingCycleAnchor.NOW)
                .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE)
                .setPaymentBehavior(SubscriptionUpdateParams.PaymentBehavior.PENDING_IF_INCOMPLETE)
                .addItem(SubscriptionUpdateParams.Item.builder()
                        .setId(item.getId())
                        .setPrice(targetPlan.getStripePriceId())
                        .setQuantity(quantity)
                        .build())
                .addExpand("latest_invoice")
                .putMetadata("clerk_user_id", clerkUserId)
                .putMetadata("pending_plan_code", targetPlan.getPlanCode())
                .putMetadata("change_type", "upgrade")
                .build();
    }

    static String resolveSubscriptionUpgradeCheckoutUrl(
            String invoiceId,
            Invoice invoice,
            String fallbackSuccessUrl) {
        if (invoice != null && hasTextStatic(invoice.getHostedInvoiceUrl())) {
            return invoice.getHostedInvoiceUrl();
        }
        if (hasTextStatic(invoiceId)) {
            try {
                Invoice refreshed = Invoice.retrieve(invoiceId);
                if (refreshed != null && hasTextStatic(refreshed.getHostedInvoiceUrl())) {
                    return refreshed.getHostedInvoiceUrl();
                }
                if (isSettledInvoice(refreshed) && hasTextStatic(fallbackSuccessUrl)) {
                    return fallbackSuccessUrl;
                }
            } catch (StripeException e) {
                throw new BillingDomainException("STRIPE_ERROR",
                        "Retrieve subscription upgrade invoice failed: " + e.getMessage(), e);
            }
        }
        if (isSettledInvoice(invoice) && hasTextStatic(fallbackSuccessUrl)) {
            return fallbackSuccessUrl;
        }
        throw new BillingDomainException(
                "STRIPE_ERROR",
                "Stripe hosted invoice URL is unavailable for subscription upgrade");
    }

    private static boolean isSettledInvoice(Invoice invoice) {
        if (invoice == null) {
            return false;
        }
        return Boolean.TRUE.equals(invoice.getPaid())
                || "paid".equalsIgnoreCase(invoice.getStatus());
    }

    private static boolean hasTextStatic(String value) {
        return value != null && !value.isBlank();
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
        if (stripeSecretKey == null || stripeSecretKey.isBlank() || stripeSecretKey.equals("sk_test_xxx")) {
            throw new BillingDomainException("STRIPE_NOT_CONFIGURED", "Stripe Secret Key is not configured");
        }
    }

    private BillingDomainException stripeFailure(String message, StripeException cause) {
        return new BillingDomainException("STRIPE_ERROR", message + ": " + cause.getMessage(), cause);
    }

    private LocalDateTime fromEpoch(Long value) {
        return value == null ? null : LocalDateTime.ofInstant(Instant.ofEpochSecond(value), ZoneOffset.UTC);
    }

    private int tierRank(String tier) {
        return switch (tier) {
            case "basic" -> 1;
            case "plus" -> 2;
            case "pro" -> 3;
            default -> 0;
        };
    }

    static PlanChangeAction classifyPlanChange(
            String currentTier,
            String currentInterval,
            String targetTier,
            String targetInterval) {
        if (currentTier == null || currentInterval == null || targetTier == null || targetInterval == null) {
            return PlanChangeAction.UNSUPPORTED;
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
        return switch (tier) {
            case "basic" -> 1;
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
