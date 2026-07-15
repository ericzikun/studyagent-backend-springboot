package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.stripe.Stripe;
import com.studyagent.common.analytics.AnalyticsEvents;
import com.studyagent.common.analytics.AnalyticsService;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import com.stripe.model.CreditNote;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceLineItem;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionSchedule;
import com.stripe.model.checkout.Session;
import com.stripe.model.testhelpers.TestClock;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionScheduleReleaseParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.RefundCreateParams;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.StripeWebhookEventEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.StripeWebhookEventMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.billing.BillingCheckoutNotifyRequest;
import com.studyagent.service.domain.billing.BillingPaymentFailedNotifyRequest;
import com.studyagent.service.domain.billing.BillingQuotaGateway;
import com.studyagent.service.domain.billing.BillingRobotNotifyGateway;
import com.studyagent.service.domain.billing.BillingEntitlementPolicy;
import com.studyagent.service.domain.quota.AddonGrantSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeBillingWebhookService {
    enum ManualUpgradeSwitchMode {
        KEEP_BILLING_CYCLE,
        RESET_CYCLE_WITH_TRIAL
    }

    record ManualUpgradeSwitchStrategy(
            ManualUpgradeSwitchMode mode,
            Long trialEndEpoch
    ) {
    }

    private static final Set<String> SUBSCRIPTION_EVENTS = Set.of(
            "customer.subscription.created",
            "customer.subscription.updated",
            "customer.subscription.deleted",
            "invoice.paid",
            "invoice.payment_succeeded",
            "invoice_payment.paid",
            "invoice.payment_failed",
            "invoice.payment_action_required"
    );
    private static final Set<String> SUBSCRIPTION_SCHEDULE_EVENTS = Set.of(
            "subscription_schedule.updated",
            "subscription_schedule.released",
            "subscription_schedule.canceled",
            "subscription_schedule.aborted",
            "subscription_schedule.completed"
    );
    private static final Set<String> CHECKOUT_EVENTS = Set.of(
            "checkout.session.completed",
            "checkout.session.expired",
            "checkout.session.async_payment_succeeded",
            "checkout.session.async_payment_failed"
    );
    private static final Set<String> PAYMENT_REVERSAL_EVENTS = Set.of(
            "charge.refunded",
            "charge.dispute.created",
            "charge.dispute.funds_withdrawn",
            "charge.dispute.funds_reinstated",
            "charge.dispute.closed",
            "credit_note.created",
            "credit_note.updated",
            "credit_note.voided"
    );

    private final StripeWebhookEventMapper webhookEventMapper;
    private final UserSubscriptionMapper userSubscriptionMapper;
    private final SubscriptionPlanMapper subscriptionPlanMapper;
    private final AddonPackageDefMapper addonPackageDefMapper;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final AnalyticsService analyticsService;
    private final ObjectProvider<BillingQuotaGateway> quotaGatewayProvider;
    private final ObjectProvider<BillingRobotNotifyGateway> billingRobotNotifyGatewayProvider;
    private final PlatformTransactionManager transactionManager;

    @Value("${billing.payment-grace-days:3}")
    private int paymentGraceDays = 3;

    @Value("${billing.webhook-retry.max-attempts:8}")
    private int webhookRetryMaxAttempts = 8;

    public boolean supports(Event event) {
        if (SUBSCRIPTION_EVENTS.contains(event.getType())) {
            return true;
        }
        if (SUBSCRIPTION_SCHEDULE_EVENTS.contains(event.getType())) {
            return true;
        }
        if (PAYMENT_REVERSAL_EVENTS.contains(event.getType())) {
            return true;
        }
        if (!CHECKOUT_EVENTS.contains(event.getType())) {
            return false;
        }
        Session session = resolve(event, Session.class);
        if (session == null || session.getMetadata() == null) {
            return false;
        }
        String purchaseType = session.getMetadata().get("purchase_type");
        return "subscription".equals(purchaseType)
                || "addon".equals(purchaseType)
                || "subscription_upgrade_manual".equals(purchaseType);
    }

    public void process(Event event) {
        receive(event);
        if (!claim(event.getId())) {
            return;
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            transaction.executeWithoutResult(status -> handle(event));
            markSucceeded(event.getId());
        } catch (RuntimeException e) {
            markFailed(event.getId(), e);
            throw e;
        }
    }

    private void handle(Event event) {
        switch (event.getType()) {
            case "checkout.session.completed" -> handleCheckoutCompleted(
                    event.getId(), event.getType(), resolveRequired(event, Session.class));
            case "checkout.session.async_payment_succeeded" -> handleCheckoutCompleted(
                    event.getId(), event.getType(), resolveRequired(event, Session.class));
            case "checkout.session.async_payment_failed" -> handleCheckoutAsyncPaymentFailed(
                    event.getType(), resolveRequired(event, Session.class));
            case "checkout.session.expired" -> handleCheckoutExpired(
                    event.getId(), event.getType(), resolveRequired(event, Session.class));
            case "customer.subscription.created", "customer.subscription.updated" ->
                    syncSubscription(event, false, false);
            case "customer.subscription.deleted" -> syncSubscription(event, true, false);
            case "subscription_schedule.updated", "subscription_schedule.released",
                    "subscription_schedule.canceled", "subscription_schedule.aborted",
                    "subscription_schedule.completed" ->
                    handleSubscriptionScheduleEvent(resolveRequired(event, SubscriptionSchedule.class));
            case "invoice.paid", "invoice.payment_succeeded" ->
                    handleInvoicePaid(
                            resolveRequired(event, Invoice.class),
                            resolveInvoiceSubscriptionId(event),
                            event.getCreated());
            case "invoice_payment.paid" -> handleInvoicePaymentPaid(event);
            case "invoice.payment_failed", "invoice.payment_action_required" ->
                    handleInvoiceFailed(
                            event.getId(),
                            resolveRequired(event, Invoice.class),
                            event.getType(),
                            resolveInvoiceSubscriptionId(event));
            case "charge.refunded" -> handleChargeRefunded(resolveRequired(event, Charge.class));
            case "charge.dispute.created", "charge.dispute.funds_withdrawn",
                    "charge.dispute.funds_reinstated", "charge.dispute.closed" ->
                    handleChargeDispute(event.getType(), resolveRequired(event, Dispute.class));
            case "credit_note.created", "credit_note.updated", "credit_note.voided" ->
                    handleCreditNote(event.getType(), resolveRequired(event, CreditNote.class));
            default -> log.info("Ignored Stripe billing event: {}", event.getType());
        }
    }

    private void handleChargeRefunded(Charge charge) {
        if (!hasText(charge.getPaymentIntent())
                || charge.getAmount() == null
                || charge.getAmountRefunded() == null
                || charge.getAmountRefunded() <= 0) {
            throw new IllegalStateException("Invalid charge.refunded payload: " + charge.getId());
        }
        RechargeOrderEntity order = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getStripePaymentIntentId, charge.getPaymentIntent())
                        .last("LIMIT 1"));
        if (order == null) {
            throw new IllegalStateException(
                    "Billing order not found for refunded charge: " + charge.getId());
        }
        long cumulativeRefund = Math.min(charge.getAmountRefunded(), charge.getAmount());
        boolean fullRefund = cumulativeRefund >= charge.getAmount();
        if ("addon".equals(order.getOrderType())) {
            quotaGateway().adjustAddonForRefund(
                    charge.getPaymentIntent(),
                    "charge:" + charge.getId() + ":" + cumulativeRefund,
                    cumulativeRefund,
                    charge.getAmount());
            updateOrderRefundState(order, fullRefund ? "refunded" : "partially_refunded", charge.getId());
            return;
        }
        if (fullRefund && suspendCurrentSubscriptionForRefund(order, charge)) {
            updateOrderRefundState(order, "refunded", "subscription_full_refund:" + charge.getId());
            return;
        }
        updateOrderRefundState(order, "review_required", "subscription_refund:" + charge.getId());
    }

    private boolean suspendCurrentSubscriptionForRefund(
            RechargeOrderEntity order,
            Charge charge) {
        if (!hasText(order.getStripeSubscriptionId()) || !hasText(order.getStripeInvoiceId())) {
            return false;
        }
        UserSubscriptionEntity current = findByUser(order.getClerkUserId());
        if (current == null
                || !order.getStripeSubscriptionId().equals(current.getStripeSubscriptionId())) {
            return false;
        }
        try {
            Subscription remote = retrieveStripeSubscription(order.getStripeSubscriptionId());
            if (remote == null || !order.getStripeInvoiceId().equals(remote.getLatestInvoice())) {
                return false;
            }
        } catch (StripeException e) {
            throw new IllegalStateException(
                    "Verify current subscription invoice for refund failed: " + charge.getId(), e);
        }
        quotaGateway().clearPlanQuota(
                order.getClerkUserId(),
                order.getStripeSubscriptionId(),
                order.getPlanCode(),
                "refund:" + charge.getId() + ":plan");
        quotaGateway().pauseAddons(
                order.getClerkUserId(),
                order.getStripeSubscriptionId(),
                "refund:" + charge.getId() + ":addons");
        userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, current.getId())
                .eq(UserSubscriptionEntity::getStripeSubscriptionId, order.getStripeSubscriptionId())
                .set(UserSubscriptionEntity::getStatus, "unpaid")
                .set(UserSubscriptionEntity::getGraceEndAt, null)
                .set(UserSubscriptionEntity::getUpdatedAt, LocalDateTime.now()));
        return true;
    }

    private void updateOrderRefundState(RechargeOrderEntity order, String status, String reason) {
        rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getId, order.getId())
                .set(RechargeOrderEntity::getStatus, status)
                .set(RechargeOrderEntity::getFailureReason, reason)
                .set(RechargeOrderEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private void handleChargeDispute(String eventType, Dispute dispute) {
        if (!hasText(dispute.getPaymentIntent())) {
            throw new IllegalStateException("Dispute has no PaymentIntent: " + dispute.getId());
        }
        RechargeOrderEntity order = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getStripePaymentIntentId, dispute.getPaymentIntent())
                        .last("LIMIT 1"));
        if (order == null) {
            throw new IllegalStateException("Billing order not found for dispute: " + dispute.getId());
        }
        boolean fundsRestored = "charge.dispute.funds_reinstated".equals(eventType)
                || ("charge.dispute.closed".equals(eventType) && "won".equals(dispute.getStatus()));
        if ("addon".equals(order.getOrderType())) {
            if (fundsRestored) {
                quotaGateway().restoreAddonAfterDispute(dispute.getPaymentIntent(), dispute.getId());
                updateOrderRefundState(order, "completed", "dispute_won:" + dispute.getId());
            } else {
                quotaGateway().freezeAddonForDispute(dispute.getPaymentIntent(), dispute.getId());
                updateOrderRefundState(order, "disputed", eventType + ":" + dispute.getId());
            }
            return;
        }
        updateOrderRefundState(order, "review_required", eventType + ":" + dispute.getId());
    }

    private void handleCreditNote(String eventType, CreditNote creditNote) {
        if (!hasText(creditNote.getInvoice())) {
            throw new IllegalStateException("Credit note has no Invoice: " + creditNote.getId());
        }
        RechargeOrderEntity order = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getStripeInvoiceId, creditNote.getInvoice())
                        .last("LIMIT 1"));
        if (order == null) {
            throw new IllegalStateException("Billing order not found for credit note: " + creditNote.getId());
        }
        updateOrderRefundState(order, "review_required", eventType + ":" + creditNote.getId());
    }

    private void handleInvoicePaymentPaid(Event event) {
        String invoiceId = resolveInvoiceIdFromInvoicePayment(event);
        if (invoiceId == null) {
            log.info("invoice_payment.paid has no invoice id, ignored: event={}", event.getId());
            return;
        }
        try {
            handleInvoicePaid(Invoice.retrieve(invoiceId), null, event.getCreated());
        } catch (StripeException e) {
            throw new IllegalStateException("Retrieve invoice failed from invoice_payment.paid: " + invoiceId, e);
        }
    }

    private void handleCheckoutCompleted(String stripeEventId, String stripeEventType, Session session) {
        Map<String, String> metadata = session.getMetadata();
        String purchaseType = metadata.get("purchase_type");
        String clerkUserId = firstNonBlank(metadata.get("clerk_user_id"), session.getClientReferenceId());
        if ("addon".equals(purchaseType)) {
            if (!"paid".equals(session.getPaymentStatus())) {
                log.info("Add-on Checkout is not paid yet: session={}, payment_status={}",
                        session.getId(), session.getPaymentStatus());
                return;
            }
            String addonCode = metadata.get("addon_code");
            RechargeOrderEntity order = requireAddonOrderSnapshot(session, clerkUserId, addonCode);
            UserSubscriptionEntity subscription = userSubscriptionMapper.selectByUser(clerkUserId);
            if (subscription == null
                    || !BillingEntitlementPolicy.allowsAddonPurchase(subscription.getStatus())) {
                refundIneligibleAddon(session.getPaymentIntent(), session.getId());
                markAddonOrderRefunded(session.getId(), "subscription_ineligible_at_fulfillment");
                capturePaymentFailed(
                        clerkUserId,
                        session,
                        addonCode,
                        "addon",
                        order.getFeatureCode(),
                        order.getQuotaAmount(),
                        "subscription_ineligible_at_fulfillment");
                return;
            }
            AddonGrantSnapshot snapshot = new AddonGrantSnapshot(
                    order.getId(),
                    order.getAddonCode(),
                    order.getFeatureCode(),
                    order.getQuotaAmount(),
                    order.getValidityMonthsSnapshot());
            quotaGateway().grantAddonFromCheckout(
                    clerkUserId,
                    snapshot,
                    session.getId(),
                    session.getPaymentIntent(),
                    resolveAddonPaidAt(clerkUserId, session));
            completeOrderBySession(session, addonCode, null);
            capturePaymentSucceeded(
                    clerkUserId,
                    session,
                    addonCode,
                    "addon",
                    snapshot.featureCode(),
                    snapshot.quotaAmount(),
                    addonCode
            );
            notifyCheckoutSucceeded(
                    stripeEventId,
                    stripeEventType,
                    session,
                    metadata,
                    purchaseType,
                    snapshot.featureCode(),
                    snapshot.quotaAmount());
            return;
        }

        if ("subscription".equals(purchaseType)) {
            boolean paymentSettled = "paid".equals(session.getPaymentStatus());
            updateCheckoutSubscriptionLink(session, clerkUserId, metadata.get("plan_code"), paymentSettled);
            if (paymentSettled && session.getSubscription() != null) {
                capturePaymentSucceeded(
                        clerkUserId,
                        session,
                        metadata.get("plan_code"),
                        "subscription",
                        "subscription",
                        0L,
                        null
                );
                notifyCheckoutSucceeded(stripeEventId, stripeEventType, session, metadata, purchaseType, "subscription", 0L);
                try {
                    syncSubscription(Subscription.retrieve(session.getSubscription()), false, false);
                } catch (StripeException e) {
                    throw new IllegalStateException("Retrieve subscription failed: " + session.getSubscription(), e);
                }
            }
            return;
        }

        if ("subscription_upgrade_manual".equals(purchaseType)) {
            handleManualUpgradeCheckoutCompleted(session, clerkUserId);
            capturePaymentSucceeded(
                    clerkUserId,
                    session,
                    metadata.get("target_plan_code"),
                    "subscription",
                    "subscription",
                    0L,
                    null
            );
            if ("paid".equals(session.getPaymentStatus())) {
                notifyCheckoutSucceeded(stripeEventId, stripeEventType, session, metadata, purchaseType, "subscription", 0L);
            }
        }
    }

    Instant resolveAddonPaidAt(String clerkUserId, Session session) {
        Instant fallbackPaidAt = Instant.ofEpochSecond(session.getCreated());
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return fallbackPaidAt;
        }

        UserSubscriptionEntity subscription = userSubscriptionMapper.selectByUser(clerkUserId);
        if (!shouldUseStripeSimulationTime(subscription)) {
            return fallbackPaidAt;
        }

        try {
            Subscription stripeSubscription = retrieveStripeSubscription(subscription.getStripeSubscriptionId());
            if (stripeSubscription == null) {
                return fallbackPaidAt;
            }
            String testClockId = stripeSubscription.getTestClock();
            if (testClockId == null || testClockId.isBlank()) {
                return fallbackPaidAt;
            }
            Long frozenTime = retrieveTestClockFrozenTime(testClockId);
            if (frozenTime == null) {
                return fallbackPaidAt;
            }
            Instant simulatedPaidAt = Instant.ofEpochSecond(frozenTime);
            return simulatedPaidAt.isAfter(fallbackPaidAt)
                    ? simulatedPaidAt
                    : fallbackPaidAt;
        } catch (StripeException e) {
            log.warn("Resolve Stripe test clock time failed for add-on checkout user {}", clerkUserId, e);
            return fallbackPaidAt;
        }
    }

    Subscription retrieveStripeSubscription(String subscriptionId) throws StripeException {
        return Subscription.retrieve(subscriptionId);
    }

    Long retrieveTestClockFrozenTime(String testClockId) throws StripeException {
        return TestClock.retrieve(testClockId).getFrozenTime();
    }

    boolean shouldUseStripeSimulationTime(UserSubscriptionEntity subscription) {
        if (subscription == null
                || subscription.getStripeSubscriptionId() == null
                || subscription.getStripeSubscriptionId().isBlank()) {
            return false;
        }
        String apiKey = Stripe.apiKey;
        return apiKey != null && apiKey.startsWith("sk_test_");
    }

    private void handleCheckoutExpired(String stripeEventId, String stripeEventType, Session session) {
        Map<String, String> metadata = session.getMetadata();
        capturePaymentFailed(
                firstNonBlank(metadata.get("clerk_user_id"), session.getClientReferenceId()),
                session,
                resolveAnalyticsPlanId(metadata),
                resolveAnalyticsPackageType(metadata),
                resolveAnalyticsFeatureCode(metadata),
                0L,
                "expired"
        );
        rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getStripeSessionId, session.getId())
                .in(RechargeOrderEntity::getStatus, List.of("pending", "pending_checkout", "checkout_created"))
                .set(RechargeOrderEntity::getStatus, "checkout_expired")
                .set(RechargeOrderEntity::getUpdatedAt, LocalDateTime.now()));
        clearPendingUpgradeCheckoutBySession(session.getId());
        notifyCheckoutExpired(stripeEventId, stripeEventType, session, metadata);
    }

    private void handleCheckoutAsyncPaymentFailed(String stripeEventType, Session session) {
        Map<String, String> metadata = session.getMetadata();
        capturePaymentFailed(
                firstNonBlank(metadata.get("clerk_user_id"), session.getClientReferenceId()),
                session,
                resolveAnalyticsPlanId(metadata),
                resolveAnalyticsPackageType(metadata),
                resolveAnalyticsFeatureCode(metadata),
                0L,
                stripeEventType);
        rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getStripeSessionId, session.getId())
                .in(RechargeOrderEntity::getStatus, List.of("pending", "pending_checkout", "checkout_created"))
                .set(RechargeOrderEntity::getStatus, "failed")
                .set(RechargeOrderEntity::getFailureReason, stripeEventType)
                .set(RechargeOrderEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private void handleManualUpgradeCheckoutCompleted(Session session, String clerkUserId) {
        if (!"paid".equals(session.getPaymentStatus())) {
            log.info("Manual subscription upgrade checkout is not paid yet: session={}, payment_status={}",
                    session.getId(), session.getPaymentStatus());
            return;
        }
        String orderNo = session.getMetadata().get("upgrade_order_no");
        RechargeOrderEntity order = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getOrderNo, orderNo)
                        .eq(RechargeOrderEntity::getOrderType, "subscription_upgrade_manual")
                        .last("LIMIT 1"));
        if (order == null || "completed".equals(order.getStatus())) {
            return;
        }
        attemptManualUpgradeSwitch(order, clerkUserId, session.getId(), session.getPaymentIntent());
    }

    public void retryManualUpgradeSwitch(String orderNo) {
        if (!hasText(orderNo)) {
            return;
        }
        RechargeOrderEntity order = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getOrderNo, orderNo)
                        .eq(RechargeOrderEntity::getOrderType, "subscription_upgrade_manual")
                        .last("LIMIT 1"));
        if (order == null || "completed".equals(order.getStatus())) {
            return;
        }
        attemptManualUpgradeSwitch(
                order,
                order.getClerkUserId(),
                order.getStripeSessionId(),
                order.getStripePaymentIntentId());
    }

    private void attemptManualUpgradeSwitch(
            RechargeOrderEntity order,
            String clerkUserId,
            String stripeSessionId,
            String stripePaymentIntentId) {
        if (!hasText(clerkUserId)) {
            throw new IllegalStateException("Manual subscription upgrade order has no clerk user id: " + order.getOrderNo());
        }
        if (!markManualUpgradeOrderSwitching(order, stripeSessionId, stripePaymentIntentId)) {
            return;
        }
        UserSubscriptionEntity current = findByUser(clerkUserId);
        Subscription subscription;
        try {
            subscription = Subscription.retrieve(order.getStripeSubscriptionId());
            if (current != null) {
                releasePendingScheduleIfPresent(current, subscription);
            }
            if (subscription.getItems() == null
                    || subscription.getItems().getData() == null
                    || subscription.getItems().getData().size() != 1) {
                throw new IllegalStateException("Subscription must contain one item");
            }
            SubscriptionItem item = subscription.getItems().getData().get(0);
            SubscriptionPlanEntity currentPlan = requirePlan(order.getPlanCode());
            SubscriptionPlanEntity targetPlan = requirePlan(order.getTargetPlanCode());
            ManualUpgradeSwitchStrategy strategy = resolveManualUpgradeSwitchStrategy(
                    order,
                    currentPlan,
                    targetPlan,
                    LocalDateTime.now(ZoneOffset.UTC));
            Subscription updated = subscription.update(buildManualUpgradeUpdateParams(
                    item,
                    targetPlan,
                    clerkUserId,
                    strategy), RequestOptions.builder()
                    .setIdempotencyKey("manual-upgrade-switch:" + order.getOrderNo())
                    .build());
            Long periodStartEpoch = resolvePeriodEpoch(null, resolveSubscriptionPeriodStart(updated));
            Long periodEndEpoch = resolvePeriodEpoch(null, resolveSubscriptionPeriodEnd(updated));
            Instant periodStart = instant(periodStartEpoch);
            Instant quotaPeriodEnd = "year".equals(targetPlan.getBillingInterval())
                    ? periodStart.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()
                    : instant(periodEndEpoch);

            syncSubscription(updated, false, false, periodStartEpoch, periodEndEpoch);
            rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                    .eq(RechargeOrderEntity::getId, order.getId())
                    .set(RechargeOrderEntity::getStatus, "switched")
                    .set(RechargeOrderEntity::getUpgradeEffectiveAt, fromEpoch(periodStartEpoch))
                    .set(RechargeOrderEntity::getUpdatedAt, LocalDateTime.now()));
            quotaGateway().grantUpgradeFromCheckout(
                    clerkUserId,
                    updated.getId(),
                    targetPlan.getPlanCode(),
                    periodStart,
                    quotaPeriodEnd,
                    order.getOrderNo());
            completeManualUpgradeOrder(order, updated, stripeSessionId, stripePaymentIntentId, periodStartEpoch);
        } catch (StripeException | IllegalStateException e) {
            rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                    .eq(RechargeOrderEntity::getId, order.getId())
                    .set(RechargeOrderEntity::getStatus, "switch_failed")
                    .set(RechargeOrderEntity::getFailureReason, e.getMessage())
                    .setSql("switch_attempts = switch_attempts + 1")
                    .set(RechargeOrderEntity::getUpdatedAt, LocalDateTime.now()));
            throw new IllegalStateException("Manual subscription upgrade switch failed", e);
        } catch (RuntimeException e) {
            rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                    .eq(RechargeOrderEntity::getId, order.getId())
                    .set(RechargeOrderEntity::getStatus, "quota_failed")
                    .set(RechargeOrderEntity::getFailureReason, e.getMessage())
                    .set(RechargeOrderEntity::getUpdatedAt, LocalDateTime.now()));
            throw e;
        }
    }

    static ManualUpgradeSwitchStrategy resolveManualUpgradeSwitchStrategy(
            RechargeOrderEntity order,
            SubscriptionPlanEntity currentPlan,
            SubscriptionPlanEntity targetPlan,
            LocalDateTime nowUtc) {
        String currentInterval = currentPlan == null ? null : currentPlan.getBillingInterval();
        String targetInterval = targetPlan == null ? null : targetPlan.getBillingInterval();
        String chargeType = order == null ? null : order.getUpgradeChargeType();
        Integer chargedAmount = order == null
                ? null
                : (order.getQuotedAmountCents() != null ? order.getQuotedAmountCents() : order.getPriceCents());
        boolean targetAnnualFullAmount = "year".equals(currentInterval)
                && "year".equals(targetInterval)
                && targetPlan != null
                && targetPlan.getPriceCents() != null
                && chargedAmount != null
                && chargedAmount.intValue() == targetPlan.getPriceCents();
        if (targetAnnualFullAmount) {
            chargeType = "annual_full";
        } else if (chargeType == null || chargeType.isBlank()) {
            if ("month".equals(currentInterval) && "month".equals(targetInterval)) {
                chargeType = "monthly_full";
            } else if ("month".equals(currentInterval) && "year".equals(targetInterval)) {
                chargeType = "annual_full";
            } else {
                chargeType = "annual_diff";
            }
        }
        if ("monthly_full".equals(chargeType)) {
            return new ManualUpgradeSwitchStrategy(
                    ManualUpgradeSwitchMode.RESET_CYCLE_WITH_TRIAL,
                    nowUtc.plusMonths(1).toEpochSecond(ZoneOffset.UTC));
        }
        if ("annual_full".equals(chargeType)) {
            return new ManualUpgradeSwitchStrategy(
                    ManualUpgradeSwitchMode.RESET_CYCLE_WITH_TRIAL,
                    nowUtc.plusYears(1).toEpochSecond(ZoneOffset.UTC));
        }
        return new ManualUpgradeSwitchStrategy(ManualUpgradeSwitchMode.KEEP_BILLING_CYCLE, null);
    }

    static SubscriptionUpdateParams buildManualUpgradeUpdateParams(
            SubscriptionItem item,
            SubscriptionPlanEntity targetPlan,
            String clerkUserId,
            ManualUpgradeSwitchStrategy strategy) {
        SubscriptionUpdateParams.Builder builder = SubscriptionUpdateParams.builder()
                .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE)
                .addItem(SubscriptionUpdateParams.Item.builder()
                        .setId(item.getId())
                        .setPrice(targetPlan.getStripePriceId())
                        .setQuantity(item.getQuantity() == null ? 1L : item.getQuantity())
                        .build())
                .putMetadata("clerk_user_id", clerkUserId)
                .putMetadata("change_type", "upgrade");
        if (strategy != null
                && strategy.mode() == ManualUpgradeSwitchMode.RESET_CYCLE_WITH_TRIAL
                && strategy.trialEndEpoch() != null) {
            builder.setTrialEnd(strategy.trialEndEpoch());
        }
        return builder.build();
    }

    private void handleInvoicePaid(Invoice invoice, String eventSubscriptionId, Long eventCreatedEpoch) {
        String subscriptionId = firstNonBlank(resolveInvoiceSubscriptionId(invoice), eventSubscriptionId);
        if (subscriptionId == null) {
            log.info("Paid invoice has no subscription, ignored: invoice={}", invoice.getId());
            return;
        }
        Subscription subscription;
        try {
            subscription = Subscription.retrieve(subscriptionId);
        } catch (StripeException e) {
            throw new IllegalStateException("Retrieve invoice subscription failed: " + subscriptionId, e);
        }
        SubscriptionPlanEntity plan = requirePlanBySubscription(subscription);
        String clerkUserId = resolveUserId(subscription);
        UserSubscriptionEntity existing = findByUser(clerkUserId);
        if (shouldIgnorePaidInvoiceSync(existing, subscriptionId, eventCreatedEpoch)) {
            log.info("Ignore stale paid invoice sync: invoice={}, subscription={}, user={}",
                    invoice.getId(), subscriptionId, clerkUserId);
            return;
        }
        RechargeOrderEntity existingInvoiceOrder = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getStripeInvoiceId, invoice.getId())
                        .last("LIMIT 1"));
        RechargeOrderEntity pendingUpgrade = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                        .eq(RechargeOrderEntity::getOrderType, "subscription_upgrade")
                        .eq(RechargeOrderEntity::getPlanCode, plan.getPlanCode())
                        .eq(RechargeOrderEntity::getStatus, "pending")
                        .orderByDesc(RechargeOrderEntity::getCreatedAt)
                        .last("LIMIT 1"));
        RechargeOrderEntity pendingInitial = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                        .eq(RechargeOrderEntity::getOrderType, "subscription_initial")
                        .eq(RechargeOrderEntity::getPlanCode, plan.getPlanCode())
                        .eq(RechargeOrderEntity::getStatus, "pending")
                        .orderByDesc(RechargeOrderEntity::getCreatedAt)
                        .last("LIMIT 1"));
        RechargeOrderEntity selectedUpgradeOrder = selectSubscriptionInvoiceOrder(
                isOrderType(existingInvoiceOrder, "subscription_upgrade") ? existingInvoiceOrder : null,
                pendingUpgrade,
                subscriptionId);
        RechargeOrderEntity selectedInitialOrder = selectSubscriptionInvoiceOrder(
                isOrderType(existingInvoiceOrder, "subscription_initial", "subscription_renewal")
                        ? existingInvoiceOrder
                        : null,
                pendingInitial,
                subscriptionId);
        boolean upgrade = isSubscriptionUpgradeInvoice(invoice, firstNonNull(selectedUpgradeOrder, existingInvoiceOrder))
                || isPendingPlanActivationUpgrade(
                existing,
                plan.getPlanCode(),
                plan.getTier(),
                invoice,
                selectedUpgradeOrder);
        RechargeOrderEntity matchingManualUpgradeOrder = findMatchingManualUpgradeOrder(
                clerkUserId,
                subscriptionId,
                invoice.getPaymentIntent(),
                plan.getPlanCode());

        Long periodStartEpoch = resolvePeriodEpoch(resolveInvoicePeriodStart(invoice), subscription.getCurrentPeriodStart());
        Long periodEndEpoch = resolvePeriodEpoch(resolveInvoicePeriodEnd(invoice), subscription.getCurrentPeriodEnd());
        Instant periodStart = instant(periodStartEpoch);
        Instant quotaPeriodEnd = "year".equals(plan.getBillingInterval())
                ? periodStart.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()
                : instant(periodEndEpoch);
        if (!shouldApplyQuotaGrantForInvoice(upgrade, matchingManualUpgradeOrder)) {
            log.info("Skip duplicate manual-upgrade quota grant for invoice={}, subscription={}, orderNo={}",
                    invoice.getId(),
                    subscriptionId,
                    matchingManualUpgradeOrder == null ? null : matchingManualUpgradeOrder.getOrderNo());
        } else if (upgrade) {
            quotaGateway().addFullPlanForUpgrade(
                    clerkUserId,
                    subscription.getId(),
                    plan.getPlanCode(),
                    periodStart,
                    quotaPeriodEnd,
                    invoice.getId());
        } else {
            quotaGateway().resetFromPaidInvoice(
                    clerkUserId,
                    subscription.getId(),
                    plan.getPlanCode(),
                    periodStart,
                    quotaPeriodEnd,
                    invoice.getId(),
                    resolvePaidInvoiceGrantType(invoice, selectedInitialOrder));
        }

        syncSubscription(subscription, false, true, periodStartEpoch, periodEndEpoch);
        if (selectedUpgradeOrder != null) {
            completeSubscriptionOrder(selectedUpgradeOrder, invoice, subscriptionId);
        } else if (selectedInitialOrder != null) {
            completeSubscriptionOrder(selectedInitialOrder, invoice, subscriptionId);
        } else {
            markInvoicePaid(invoice, subscriptionId, clerkUserId, plan, upgrade);
        }
        quotaGateway().resumeEligibleAddons(
                clerkUserId,
                subscription.getId(),
                "subscription:" + subscription.getId() + ":resume-addons:" + invoice.getId());
    }

    private void handleInvoiceFailed(String stripeEventId, Invoice invoice, String eventType, String eventSubscriptionId) {
        String subscriptionId = firstNonBlank(resolveInvoiceSubscriptionId(invoice), eventSubscriptionId);
        if (subscriptionId == null) {
            return;
        }
        UserSubscriptionEntity entity = userSubscriptionMapper.selectOne(
                new LambdaQueryWrapper<UserSubscriptionEntity>()
                        .eq(UserSubscriptionEntity::getStripeSubscriptionId, subscriptionId)
                        .last("LIMIT 1"));
        if (entity != null) {
            LocalDateTime now = LocalDateTime.now();
            userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                    .eq(UserSubscriptionEntity::getId, entity.getId())
                    .set(UserSubscriptionEntity::getStatus,
                            "invoice.payment_action_required".equals(eventType) ? "incomplete" : "past_due")
                    .set("invoice.payment_failed".equals(eventType),
                            UserSubscriptionEntity::getGraceEndAt,
                            calculateGraceEnd(now, paymentGraceDays))
                    .set(UserSubscriptionEntity::getLastSyncedAt, now)
                    .set(UserSubscriptionEntity::getUpdatedAt, now));
        }
        RechargeOrderEntity order = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getStripeInvoiceId, invoice.getId())
                        .last("LIMIT 1"));
        if (order == null && entity != null) {
            order = rechargeOrderMapper.selectOne(
                    new LambdaQueryWrapper<RechargeOrderEntity>()
                            .eq(RechargeOrderEntity::getClerkUserId, entity.getClerkUserId())
                            .in(RechargeOrderEntity::getOrderType,
                                    "subscription_initial", "subscription_upgrade", "subscription_renewal")
                            .in(RechargeOrderEntity::getStatus, "pending", "failed")
                            .orderByDesc(RechargeOrderEntity::getCreatedAt)
                            .last("LIMIT 1"));
        }
        if (order != null) {
            rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                    .eq(RechargeOrderEntity::getId, order.getId())
                    .set(RechargeOrderEntity::getStripeInvoiceId, invoice.getId())
                    .set(RechargeOrderEntity::getStripeSubscriptionId, subscriptionId)
                    .set(RechargeOrderEntity::getStripePaymentIntentId, invoice.getPaymentIntent())
                    .set(RechargeOrderEntity::getStatus,
                            "invoice.payment_action_required".equals(eventType) ? "pending" : "failed")
                    .set(RechargeOrderEntity::getFailureReason, eventType)
                    .set(RechargeOrderEntity::getUpdatedAt, LocalDateTime.now()));
        }
        captureInvoicePaymentFailed(entity, order, invoice, eventType);
        notifyInvoicePaymentFailed(stripeEventId, eventType, entity, order, invoice);
        if ("invoice.payment_failed".equals(eventType) && entity != null) {
            clearPendingUpgradeState(entity, order, eventType);
        }
    }

    boolean markManualUpgradeOrderSwitching(
            RechargeOrderEntity order,
            String stripeSessionId,
            String stripePaymentIntentId) {
        LocalDateTime now = LocalDateTime.now();
        return rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getId, order.getId())
                .in(RechargeOrderEntity::getStatus, List.of(
                        "pending",
                        "pending_checkout",
                        "checkout_created",
                        "checkout_expired",
                        "payment_failed",
                        "paid",
                        "switch_failed"))
                .set(RechargeOrderEntity::getStatus, "switching")
                .set(hasText(stripeSessionId), RechargeOrderEntity::getStripeSessionId, stripeSessionId)
                .set(hasText(stripePaymentIntentId), RechargeOrderEntity::getStripePaymentIntentId, stripePaymentIntentId)
                .set(order.getPaidAt() == null, RechargeOrderEntity::getPaidAt, now)
                .set(RechargeOrderEntity::getFailureReason, null)
                .set(RechargeOrderEntity::getUpdatedAt, now)) == 1;
    }

    private void completeManualUpgradeOrder(
            RechargeOrderEntity order,
            Subscription updated,
            String stripeSessionId,
            String stripePaymentIntentId,
            Long periodStartEpoch) {
        LocalDateTime now = LocalDateTime.now();
        rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getId, order.getId())
                .set(RechargeOrderEntity::getStatus, "completed")
                .set(RechargeOrderEntity::getStripeSubscriptionId, updated.getId())
                .set(hasText(stripeSessionId), RechargeOrderEntity::getStripeSessionId, stripeSessionId)
                .set(hasText(stripePaymentIntentId), RechargeOrderEntity::getStripePaymentIntentId, stripePaymentIntentId)
                .set(RechargeOrderEntity::getUpgradeEffectiveAt, fromEpoch(periodStartEpoch))
                .set(RechargeOrderEntity::getFailureReason, null)
                .set(RechargeOrderEntity::getUpdatedAt, now));
        clearPendingUpgradeCheckoutByOrderNo(order.getOrderNo());
    }

    private void clearPendingUpgradeCheckoutBySession(String stripeSessionId) {
        RechargeOrderEntity order = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getStripeSessionId, stripeSessionId)
                        .eq(RechargeOrderEntity::getOrderType, "subscription_upgrade_manual")
                        .last("LIMIT 1"));
        if (order != null) {
            clearPendingUpgradeCheckoutByOrderNo(order.getOrderNo());
        }
    }

    private void clearPendingUpgradeCheckoutByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return;
        }
        userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getPendingUpgradeOrderNo, orderNo)
                .set(UserSubscriptionEntity::getPendingUpgradeOrderNo, null)
                .set(UserSubscriptionEntity::getPendingUpgradeExpiresAt, null)
                .set(UserSubscriptionEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private void syncSubscription(Subscription subscription, boolean deleted, boolean activatePendingPlan) {
        syncSubscription(subscription, deleted, activatePendingPlan, null, null);
    }

    private void syncSubscription(Event event, boolean deleted, boolean activatePendingPlan) {
        Subscription subscription = resolveRequired(event, Subscription.class);
        String clerkUserId = resolveUserId(subscription);
        UserSubscriptionEntity existing = findByUser(clerkUserId);
        if (isStaleSubscriptionEvent(existing, event.getCreated())) {
            log.info("Ignore stale Stripe subscription event: event={}, subscription={}, user={}",
                    event.getId(), subscription.getId(), clerkUserId);
            return;
        }
        syncSubscription(
                subscription,
                deleted,
                activatePendingPlan,
                resolveSubscriptionPeriodStart(event),
                resolveSubscriptionPeriodEnd(event));
        if (event.getCreated() != null) {
            userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                    .eq(UserSubscriptionEntity::getClerkUserId, clerkUserId)
                    .and(wrapper -> wrapper.isNull(UserSubscriptionEntity::getLastStripeEventCreatedAt)
                            .or()
                            .le(UserSubscriptionEntity::getLastStripeEventCreatedAt, event.getCreated()))
                    .set(UserSubscriptionEntity::getLastStripeEventCreatedAt, event.getCreated())
                    .set(UserSubscriptionEntity::getLastStripeEventId, event.getId())
                    .set(UserSubscriptionEntity::getUpdatedAt, LocalDateTime.now()));
        }
    }

    static boolean isStaleSubscriptionEvent(UserSubscriptionEntity existing, Long eventCreatedAt) {
        return existing != null
                && existing.getLastStripeEventCreatedAt() != null
                && eventCreatedAt != null
                && eventCreatedAt < existing.getLastStripeEventCreatedAt();
    }

    private void syncSubscription(
            Subscription subscription,
            boolean deleted,
            boolean activatePendingPlan,
            Long periodStartOverride,
            Long periodEndOverride) {
        String clerkUserId = resolveUserId(subscription);
        UserSubscriptionEntity existing = findByUser(clerkUserId);
        if (deleted && existing != null
                && existing.getStripeSubscriptionId() != null
                && !subscription.getId().equals(existing.getStripeSubscriptionId())) {
            log.warn("Ignored stale subscription.deleted: user={}, current={}, event={}",
                    clerkUserId, existing.getStripeSubscriptionId(), subscription.getId());
            return;
        }
        String previousPlanCode = existing == null ? null : existing.getPlanCode();

        SubscriptionPlanEntity plan = deleted ? null : requirePlanBySubscription(subscription);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            existing = new UserSubscriptionEntity();
            existing.setClerkUserId(clerkUserId);
            existing.setVersion(0);
            existing.setCreatedAt(now);
            existing.setUpdatedAt(now);
            applySubscription(existing, subscription, plan, deleted, activatePendingPlan,
                    periodStartOverride, periodEndOverride, now);
            userSubscriptionMapper.insert(existing);
        } else {
            applySubscription(existing, subscription, plan, deleted, activatePendingPlan,
                    periodStartOverride, periodEndOverride, now);
            updateSubscriptionEntity(existing, deleted);
        }

        if (deleted) {
            quotaGateway().clearPlanQuota(
                    clerkUserId,
                    subscription.getId(),
                    previousPlanCode,
                    "subscription:" + subscription.getId() + ":deleted");
            quotaGateway().pauseAddons(
                    clerkUserId,
                    subscription.getId(),
                    "subscription:" + subscription.getId() + ":pause-addons");
        }
    }

    void handleSubscriptionScheduleEvent(SubscriptionSchedule schedule) {
        String subscriptionId = resolveScheduleSubscriptionId(schedule);
        if (!hasText(subscriptionId)) {
            log.info("Ignore schedule event without subscription binding: schedule={}", schedule == null ? null : schedule.getId());
            return;
        }
        UserSubscriptionEntity existing = userSubscriptionMapper.selectOne(
                new LambdaQueryWrapper<UserSubscriptionEntity>()
                        .eq(UserSubscriptionEntity::getStripeSubscriptionId, subscriptionId)
                        .last("LIMIT 1"));
        if (existing == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        applyScheduleState(existing, schedule, now);
        userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, existing.getId())
                .set(UserSubscriptionEntity::getStripeScheduleId, existing.getStripeScheduleId())
                .set(UserSubscriptionEntity::getPendingPlanCode, existing.getPendingPlanCode())
                .set(UserSubscriptionEntity::getPendingEffectiveAt, existing.getPendingEffectiveAt())
                .set(UserSubscriptionEntity::getLastSyncedAt, now)
                .set(UserSubscriptionEntity::getUpdatedAt, now));
    }

    void applySubscription(
            UserSubscriptionEntity entity,
            Subscription subscription,
            SubscriptionPlanEntity plan,
            boolean deleted,
            boolean activatePendingPlan,
            Long periodStartOverride,
            Long periodEndOverride,
            LocalDateTime now) {
        String resolvedPlanCode = plan == null ? null : plan.getPlanCode();
        boolean pendingPlanMatchesResolvedPlan = entity.getPendingPlanCode() != null
                && resolvedPlanCode != null
                && entity.getPendingPlanCode().equals(resolvedPlanCode);
        boolean pendingActivationNotPaid = !deleted
                && !activatePendingPlan
                && pendingPlanMatchesResolvedPlan
                && resolvedPlanCode != null
                && !resolvedPlanCode.equals(entity.getPlanCode());
        boolean staleDeferredPlan = !deleted
                && !hasText(subscription.getSchedule())
                && entity.getPendingPlanCode() != null
                && !pendingPlanMatchesResolvedPlan;
        if (!deleted || hasText(subscription.getCustomer())) {
            entity.setStripeCustomerId(subscription.getCustomer());
        }
        entity.setStatus(deleted ? "canceled" : subscription.getStatus());
        if (!deleted && ("active".equals(subscription.getStatus()) || "trialing".equals(subscription.getStatus()))) {
            entity.setGraceEndAt(null);
        }
        if (deleted) {
            normalizeDeletedSubscriptionState(entity);
        } else {
            entity.setStripeSubscriptionId(subscription.getId());
        }
        if (!deleted && !pendingActivationNotPaid) {
            entity.setTier(deleted ? "free" : plan.getTier());
            entity.setPlanCode(deleted ? null : plan.getPlanCode());
            entity.setCurrentPeriodStart(fromEpoch(resolvePeriodEpoch(periodStartOverride, resolveSubscriptionPeriodStart(subscription))));
            entity.setCurrentPeriodEnd(fromEpoch(resolvePeriodEpoch(periodEndOverride, resolveSubscriptionPeriodEnd(subscription))));
        }
        entity.setCancelAtPeriodEnd(!deleted && Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()));
        entity.setStripeScheduleId(resolveMirroredScheduleId(
                subscription.getSchedule(),
                deleted,
                activatePendingPlan,
                pendingPlanMatchesResolvedPlan));
        if ((activatePendingPlan && !deleted && pendingPlanMatchesResolvedPlan) || staleDeferredPlan) {
            entity.setPendingPlanCode(null);
            entity.setPendingEffectiveAt(null);
        }
        if (!deleted && !pendingActivationNotPaid) {
            LocalDateTime quotaPeriodStart = fromEpoch(resolvePeriodEpoch(periodStartOverride, resolveSubscriptionPeriodStart(subscription)));
            LocalDateTime subscriptionPeriodEnd = fromEpoch(resolvePeriodEpoch(periodEndOverride, resolveSubscriptionPeriodEnd(subscription)));
            entity.setQuotaPeriodStart(quotaPeriodStart);
            entity.setQuotaPeriodEnd("year".equals(plan.getBillingInterval())
                    ? quotaPeriodStart.plusMonths(1)
                    : subscriptionPeriodEnd);
        } else if (deleted) {
            entity.setQuotaPeriodStart(null);
            entity.setQuotaPeriodEnd(null);
        }
        entity.setLastSyncedAt(now);
        entity.setUpdatedAt(now);
    }

    void applyScheduleState(
            UserSubscriptionEntity entity,
            SubscriptionSchedule schedule,
            LocalDateTime now) {
        if (entity == null || schedule == null || !hasText(schedule.getId())) {
            return;
        }
        String status = schedule.getStatus();
        String pendingPlanCode = schedule.getMetadata() == null ? null : schedule.getMetadata().get("pending_plan_code");
        if ("active".equals(status) || "not_started".equals(status)) {
            entity.setStripeScheduleId(schedule.getId());
            if (hasText(pendingPlanCode)) {
                entity.setPendingPlanCode(pendingPlanCode);
                if (entity.getPendingEffectiveAt() == null) {
                    LocalDateTime effectiveAt = fromEpoch(resolveScheduleCurrentPhaseEnd(schedule));
                    entity.setPendingEffectiveAt(effectiveAt != null ? effectiveAt : entity.getCurrentPeriodEnd());
                }
            }
        } else if (schedule.getId().equals(entity.getStripeScheduleId())) {
            entity.setStripeScheduleId(null);
            entity.setPendingPlanCode(null);
            entity.setPendingEffectiveAt(null);
        }
        entity.setLastSyncedAt(now);
        entity.setUpdatedAt(now);
    }

    static void normalizeDeletedSubscriptionState(UserSubscriptionEntity entity) {
        entity.setTier("free");
        entity.setPlanCode(null);
        entity.setStripeSubscriptionId(null);
        entity.setStripeScheduleId(null);
        entity.setCurrentPeriodStart(null);
        entity.setCurrentPeriodEnd(null);
        entity.setQuotaPeriodStart(null);
        entity.setQuotaPeriodEnd(null);
        entity.setCancelAtPeriodEnd(false);
        entity.setPendingPlanCode(null);
        entity.setPendingEffectiveAt(null);
        entity.setPendingUpgradeOrderNo(null);
        entity.setPendingUpgradeExpiresAt(null);
        entity.setGraceEndAt(null);
    }

    static LocalDateTime calculateGraceEnd(LocalDateTime failedAt, int graceDays) {
        return failedAt.plusDays(Math.max(0, graceDays));
    }

    static String resolveMirroredScheduleId(
            String remoteScheduleId,
            boolean deleted,
            boolean activatePendingPlan,
            boolean pendingPlanMatchesResolvedPlan) {
        if (deleted) {
            return null;
        }
        if (activatePendingPlan && pendingPlanMatchesResolvedPlan) {
            return null;
        }
        return remoteScheduleId == null || remoteScheduleId.isBlank() ? null : remoteScheduleId;
    }

    private void updateSubscriptionEntity(UserSubscriptionEntity entity, boolean deleted) {
        LambdaUpdateWrapper<UserSubscriptionEntity> update = new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, entity.getId())
                .set(UserSubscriptionEntity::getStripeCustomerId, entity.getStripeCustomerId())
                .set(UserSubscriptionEntity::getStripeSubscriptionId, entity.getStripeSubscriptionId())
                .set(UserSubscriptionEntity::getStripeScheduleId, entity.getStripeScheduleId())
                .set(UserSubscriptionEntity::getTier, entity.getTier())
                .set(UserSubscriptionEntity::getStatus, entity.getStatus())
                .set(UserSubscriptionEntity::getCancelAtPeriodEnd, entity.getCancelAtPeriodEnd())
                .set(UserSubscriptionEntity::getCurrentPeriodStart, entity.getCurrentPeriodStart())
                .set(UserSubscriptionEntity::getCurrentPeriodEnd, entity.getCurrentPeriodEnd())
                .set(UserSubscriptionEntity::getQuotaPeriodStart, entity.getQuotaPeriodStart())
                .set(UserSubscriptionEntity::getQuotaPeriodEnd, entity.getQuotaPeriodEnd())
                .set(UserSubscriptionEntity::getPendingEffectiveAt, entity.getPendingEffectiveAt())
                .set(UserSubscriptionEntity::getPendingUpgradeOrderNo, entity.getPendingUpgradeOrderNo())
                .set(UserSubscriptionEntity::getPendingUpgradeExpiresAt, entity.getPendingUpgradeExpiresAt())
                .set(UserSubscriptionEntity::getGraceEndAt, entity.getGraceEndAt())
                .set(UserSubscriptionEntity::getLastSyncedAt, entity.getLastSyncedAt())
                .set(UserSubscriptionEntity::getUpdatedAt, entity.getUpdatedAt());
        if (deleted) {
            update.set(UserSubscriptionEntity::getPlanCode, null)
                    .set(UserSubscriptionEntity::getPendingPlanCode, null);
        } else {
            update.set(UserSubscriptionEntity::getPlanCode, entity.getPlanCode())
                    .set(UserSubscriptionEntity::getPendingPlanCode, entity.getPendingPlanCode());
        }
        userSubscriptionMapper.update(null, update);
    }

    private void updateCheckoutSubscriptionLink(
            Session session,
            String clerkUserId,
            String planCode,
            boolean paymentSettled) {
        UserSubscriptionEntity existing = findByUser(clerkUserId);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            existing = new UserSubscriptionEntity();
            existing.setClerkUserId(clerkUserId);
            existing.setTier("free");
            existing.setStatus("incomplete");
            existing.setStripeCustomerId(session.getCustomer());
            existing.setStripeSubscriptionId(session.getSubscription());
            existing.setCancelAtPeriodEnd(false);
            existing.setPendingPlanCode(paymentSettled ? planCode : null);
            existing.setVersion(0);
            existing.setCreatedAt(now);
            existing.setUpdatedAt(now);
            userSubscriptionMapper.insert(existing);
        } else {
            userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                    .eq(UserSubscriptionEntity::getId, existing.getId())
                    .set(UserSubscriptionEntity::getStripeCustomerId, session.getCustomer())
                    .set(UserSubscriptionEntity::getStripeSubscriptionId, session.getSubscription())
                    .set(UserSubscriptionEntity::getStatus, paymentSettled ? existing.getStatus() : "incomplete")
                    .set(UserSubscriptionEntity::getPendingPlanCode, paymentSettled ? planCode : null)
                    .set(!paymentSettled, UserSubscriptionEntity::getPendingEffectiveAt, null)
                    .set(UserSubscriptionEntity::getUpdatedAt, now));
        }
        rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getStripeSessionId, session.getId())
                .set(RechargeOrderEntity::getStripeSubscriptionId, session.getSubscription())
                .set(RechargeOrderEntity::getUpdatedAt, now));
    }

    private void completeOrderBySession(Session session, String packageCode, String planCode) {
        rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getStripeSessionId, session.getId())
                .set(RechargeOrderEntity::getPackageCode, packageCode)
                .set(RechargeOrderEntity::getPlanCode, planCode)
                .set(RechargeOrderEntity::getStripePaymentIntentId, session.getPaymentIntent())
                .set(session.getInvoice() != null && !session.getInvoice().isBlank(),
                        RechargeOrderEntity::getStripeInvoiceId,
                        session.getInvoice())
                .set(RechargeOrderEntity::getStatus, "completed")
                .set(RechargeOrderEntity::getPaidAt, LocalDateTime.now())
                .set(RechargeOrderEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private void capturePaymentSucceeded(
            String clerkUserId,
            Session session,
            String planId,
            String packageType,
            String featureCode,
            Long quotaAmount,
            String rechargePackageCode) {
        Map<String, Object> paymentProps = buildBillingPaymentProps(
                session.getId(),
                session.getPaymentIntent(),
                planId,
                packageType,
                featureCode,
                quotaAmount,
                session.getAmountTotal() == null ? 0 : session.getAmountTotal().intValue(),
                session.getCurrency() == null ? "usd" : session.getCurrency(),
                session.getCustomerDetails() == null ? null : session.getCustomerDetails().getEmail()
        );
        String resolvedUserId = hasText(clerkUserId) ? clerkUserId : "unknown";
        analyticsService.capture(resolvedUserId, AnalyticsEvents.PAYMENT_COMPLETED, paymentProps);
        analyticsService.capture(resolvedUserId, AnalyticsEvents.BILLING_PAYMENT_SUCCEEDED, paymentProps);

    }

    private void capturePaymentFailed(
            String clerkUserId,
            Session session,
            String planId,
            String packageType,
            String featureCode,
            Long quotaAmount,
            String failureReason) {
        Map<String, Object> failedProps = buildBillingPaymentProps(
                session.getId(),
                session.getPaymentIntent(),
                planId,
                packageType,
                featureCode,
                quotaAmount,
                session.getAmountTotal() == null ? 0 : session.getAmountTotal().intValue(),
                session.getCurrency() == null ? "usd" : session.getCurrency(),
                session.getCustomerDetails() == null ? null : session.getCustomerDetails().getEmail()
        );
        failedProps.put("failure_reason", failureReason);
        analyticsService.capture(
                hasText(clerkUserId) ? clerkUserId : "unknown",
                AnalyticsEvents.BILLING_PAYMENT_FAILED,
                failedProps
        );
    }

    private void captureInvoicePaymentFailed(
            UserSubscriptionEntity entity,
            RechargeOrderEntity order,
            Invoice invoice,
            String eventType) {
        String clerkUserId = entity != null ? entity.getClerkUserId() : order != null ? order.getClerkUserId() : null;
        String planId = resolveInvoiceFailedPlanId(entity, order);
        Long quotaAmount = order != null && order.getQuotaAmount() != null
                ? order.getQuotaAmount()
                : 0L;
        int amountCents = order != null && order.getPriceCents() != null
                ? order.getPriceCents()
                : 0;
        String currency = order != null && hasText(order.getCurrency())
                ? order.getCurrency()
                : (invoice.getCurrency() == null ? "usd" : invoice.getCurrency());
        Map<String, Object> failedProps = buildBillingPaymentProps(
                order != null ? order.getStripeSessionId() : null,
                invoice.getPaymentIntent(),
                planId,
                "subscription",
                "subscription",
                quotaAmount,
                amountCents,
                currency,
                null
        );
        failedProps.put("stripe_invoice_id", invoice.getId());
        failedProps.put("failure_reason", eventType);
        analyticsService.capture(
                hasText(clerkUserId) ? clerkUserId : "unknown",
                AnalyticsEvents.BILLING_PAYMENT_FAILED,
                failedProps
        );
    }

    private Map<String, Object> buildBillingPaymentProps(
            String sessionId,
            String paymentIntentId,
            String planId,
            String packageType,
            String featureCode,
            Long quotaAmount,
            int amountCents,
            String currency,
            String customerEmail) {
        Map<String, Object> props = new HashMap<>();
        props.put("session_id", sessionId);
        props.put("checkout_session_id", sessionId);
        props.put("payment_intent_id", paymentIntentId);
        props.put("plan_id", planId);
        props.put("package_type", packageType);
        props.put("feature_code", featureCode);
        props.put("quota_amount", quotaAmount);
        props.put("amount", amountCents);
        props.put("price_cents", amountCents);
        props.put("currency", currency);
        props.put("customer_email", customerEmail);
        return props;
    }

    private void markInvoicePaid(
            Invoice invoice,
            String subscriptionId,
            String clerkUserId,
            SubscriptionPlanEntity plan,
            boolean upgrade) {
        RechargeOrderEntity existing = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getStripeInvoiceId, invoice.getId())
                        .last("LIMIT 1"));
        if (existing != null) {
            completeSubscriptionOrder(existing, invoice, subscriptionId);
            return;
        }
        RechargeOrderEntity order = new RechargeOrderEntity();
        LocalDateTime now = LocalDateTime.now();
        order.setOrderNo(generateOrderNo());
        order.setOrderType(upgrade ? "subscription_upgrade" : resolveInvoiceOrderType(invoice));
        order.setClerkUserId(clerkUserId);
        order.setFeatureCode("subscription");
        order.setPackageCode(plan.getPlanCode());
        order.setPlanCode(plan.getPlanCode());
        order.setQuotaAmount(0L);
        order.setPriceCents(invoice.getAmountPaid() == null ? 0 : invoice.getAmountPaid().intValue());
        order.setCurrency(invoice.getCurrency() == null ? "usd" : invoice.getCurrency());
        order.setStripePaymentIntentId(invoice.getPaymentIntent());
        order.setStripeInvoiceId(invoice.getId());
        order.setStripeSubscriptionId(subscriptionId);
        order.setStatus("completed");
        order.setPaidAt(now);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        try {
            rechargeOrderMapper.insert(order);
        } catch (DuplicateKeyException ignored) {
            log.info("Invoice order already exists: {}", invoice.getId());
        }
    }

    private void completeSubscriptionOrder(RechargeOrderEntity order, Invoice invoice, String subscriptionId) {
        RechargeOrderEntity existingInvoiceOrder = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getStripeInvoiceId, invoice.getId())
                        .last("LIMIT 1"));
        if (existingInvoiceOrder != null
                && existingInvoiceOrder.getId() != null
                && !existingInvoiceOrder.getId().equals(order.getId())) {
            log.info("Invoice {} already linked to order {}, skip duplicate completion for order {}",
                    invoice.getId(), existingInvoiceOrder.getOrderNo(), order.getOrderNo());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getId, order.getId())
                .set(RechargeOrderEntity::getStripeInvoiceId, invoice.getId())
                .set(RechargeOrderEntity::getStripeSubscriptionId, subscriptionId)
                .set(RechargeOrderEntity::getStripePaymentIntentId, invoice.getPaymentIntent())
                .set(RechargeOrderEntity::getStatus, "completed")
                .set(RechargeOrderEntity::getFailureReason, null)
                .set(RechargeOrderEntity::getPaidAt, now)
                .set(RechargeOrderEntity::getUpdatedAt, now));
    }

    String resolveInvoiceOrderType(Invoice invoice) {
        if (isSubscriptionUpdateBillingReason(invoice)) {
            return "subscription_upgrade";
        }
        return "subscription_create".equals(invoice.getBillingReason())
                ? "subscription_initial"
                : "subscription_renewal";
    }

    boolean isSubscriptionUpgradeInvoice(Invoice invoice, RechargeOrderEntity order) {
        return isSubscriptionUpdateBillingReason(invoice)
                || (order != null && "subscription_upgrade".equals(order.getOrderType()));
    }

    static boolean shouldApplyQuotaGrantForInvoice(boolean upgrade, RechargeOrderEntity matchingManualUpgradeOrder) {
        if (!upgrade || matchingManualUpgradeOrder == null) {
            return true;
        }
        if (!"subscription_upgrade_manual".equals(matchingManualUpgradeOrder.getOrderType())) {
            return true;
        }
        return !Set.of("paid", "switching", "switched", "completed")
                .contains(matchingManualUpgradeOrder.getStatus());
    }

    static RechargeOrderEntity selectSubscriptionInvoiceOrder(
            RechargeOrderEntity existingInvoiceOrder,
            RechargeOrderEntity pendingOrder,
            String subscriptionId) {
        if (existingInvoiceOrder != null) {
            return existingInvoiceOrder;
        }
        if (pendingOrder == null) {
            return null;
        }
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return pendingOrder;
        }
        if (pendingOrder.getStripeSubscriptionId() == null
                || pendingOrder.getStripeSubscriptionId().isBlank()
                || subscriptionId.equals(pendingOrder.getStripeSubscriptionId())) {
            return pendingOrder;
        }
        return null;
    }

    static boolean shouldIgnorePaidInvoiceSync(
            UserSubscriptionEntity existing,
            String subscriptionId,
            Long eventCreatedEpoch) {
        if (existing == null) {
            return false;
        }
        if (existing.getStripeSubscriptionId() != null
                && !existing.getStripeSubscriptionId().isBlank()
                && subscriptionId != null
                && !subscriptionId.isBlank()
                && !subscriptionId.equals(existing.getStripeSubscriptionId())) {
            return true;
        }
        if (!"canceled".equalsIgnoreCase(existing.getStatus())
                || existing.getLastSyncedAt() == null
                || eventCreatedEpoch == null) {
            return false;
        }
        return eventCreatedEpoch < existing.getLastSyncedAt().toEpochSecond(ZoneOffset.UTC);
    }

    private static RechargeOrderEntity firstNonNull(RechargeOrderEntity primary, RechargeOrderEntity fallback) {
        return primary != null ? primary : fallback;
    }

    private static boolean isOrderType(RechargeOrderEntity order, String... expectedTypes) {
        if (order == null || order.getOrderType() == null || order.getOrderType().isBlank()) {
            return false;
        }
        for (String expectedType : expectedTypes) {
            if (expectedType.equals(order.getOrderType())) {
                return true;
            }
        }
        return false;
    }

    static String resolvePaidInvoiceGrantType(Invoice invoice, RechargeOrderEntity selectedOrder) {
        if ((invoice != null && "subscription_create".equals(invoice.getBillingReason()))
                || isOrderType(selectedOrder, "subscription_initial")) {
            return "subscription_initial";
        }
        return "subscription_renewal";
    }

    static boolean isPendingPlanActivationUpgrade(
            UserSubscriptionEntity existing,
            String resolvedPlanCode,
            String resolvedPlanTier,
            Invoice invoice,
            RechargeOrderEntity pendingUpgrade) {
        if (existing == null
                || existing.getPlanCode() == null
                || existing.getTier() == null
                || existing.getPendingPlanCode() == null
                || resolvedPlanCode == null
                || resolvedPlanTier == null
                || resolvedPlanCode.equals(existing.getPlanCode())
                || !resolvedPlanCode.equals(existing.getPendingPlanCode())
                || tierRank(resolvedPlanTier) <= tierRank(existing.getTier())) {
            return false;
        }
        return invoice != null && "subscription_update".equals(invoice.getBillingReason())
                || (pendingUpgrade != null && "subscription_upgrade".equals(pendingUpgrade.getOrderType()));
    }

    Long resolveSubscriptionPeriodStart(Subscription subscription) {
        return resolveSubscriptionPeriod(subscription, true);
    }

    Long resolveSubscriptionPeriodEnd(Subscription subscription) {
        return resolveSubscriptionPeriod(subscription, false);
    }

    private Long resolveSubscriptionPeriod(Subscription subscription, boolean start) {
        if (subscription == null) {
            return null;
        }
        Long fromRoot = start ? subscription.getCurrentPeriodStart() : subscription.getCurrentPeriodEnd();
        if (fromRoot != null) {
            return fromRoot;
        }
        JsonObject raw = subscription.getRawJsonObject();
        if (raw == null || !raw.has("items") || !raw.get("items").isJsonObject()) {
            return null;
        }
        JsonObject items = raw.getAsJsonObject("items");
        if (!items.has("data") || !items.get("data").isJsonArray() || items.getAsJsonArray("data").isEmpty()) {
            return null;
        }
        JsonElement first = items.getAsJsonArray("data").get(0);
        return first.isJsonObject()
                ? readLong(first.getAsJsonObject(), start ? "current_period_start" : "current_period_end")
                : null;
    }

    void clearPendingUpgradeState(UserSubscriptionEntity entity, RechargeOrderEntity order, String reason) {
        if (entity == null) {
            return;
        }
        if (!isSubscriptionUpgradeOrder(order)
                && !hasText(entity.getPendingPlanCode())
                && !hasText(entity.getPendingUpgradeOrderNo())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, entity.getId())
                .set(UserSubscriptionEntity::getPendingPlanCode, null)
                .set(UserSubscriptionEntity::getPendingEffectiveAt, null)
                .set(UserSubscriptionEntity::getPendingUpgradeOrderNo, null)
                .set(UserSubscriptionEntity::getPendingUpgradeExpiresAt, null)
                .set(UserSubscriptionEntity::getUpdatedAt, now));
        entity.setPendingPlanCode(null);
        entity.setPendingEffectiveAt(null);
        entity.setPendingUpgradeOrderNo(null);
        entity.setPendingUpgradeExpiresAt(null);
    }

    private boolean isSubscriptionUpdateBillingReason(Invoice invoice) {
        return invoice != null && "subscription_update".equals(invoice.getBillingReason());
    }

    private boolean isSubscriptionUpgradeOrder(RechargeOrderEntity order) {
        return order != null
                && ("subscription_upgrade".equals(order.getOrderType())
                || "subscription_upgrade_manual".equals(order.getOrderType()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private RechargeOrderEntity findMatchingManualUpgradeOrder(
            String clerkUserId,
            String subscriptionId,
            String paymentIntentId,
            String targetPlanCode) {
        if (!hasText(clerkUserId) || !hasText(targetPlanCode)) {
            return null;
        }
        LambdaQueryWrapper<RechargeOrderEntity> query = new LambdaQueryWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                .eq(RechargeOrderEntity::getOrderType, "subscription_upgrade_manual")
                .eq(RechargeOrderEntity::getTargetPlanCode, targetPlanCode);
        if (hasText(paymentIntentId)) {
            query.eq(RechargeOrderEntity::getStripePaymentIntentId, paymentIntentId);
        } else if (hasText(subscriptionId)) {
            query.eq(RechargeOrderEntity::getStripeSubscriptionId, subscriptionId);
        } else {
            return null;
        }
        return rechargeOrderMapper.selectOne(query
                .orderByDesc(RechargeOrderEntity::getUpdatedAt)
                .last("LIMIT 1"));
    }

    private SubscriptionPlanEntity requirePlan(String planCode) {
        SubscriptionPlanEntity plan = subscriptionPlanMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPlanEntity>()
                        .eq(SubscriptionPlanEntity::getPlanCode, planCode)
                        .eq(SubscriptionPlanEntity::getIsActive, true)
                        .last("LIMIT 1"));
        if (plan == null) {
            throw new IllegalStateException("Unknown or inactive plan code: " + planCode);
        }
        return plan;
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

    private SubscriptionPlanEntity requirePlanBySubscription(Subscription subscription) {
        if (subscription.getItems() == null
                || subscription.getItems().getData() == null
                || subscription.getItems().getData().isEmpty()) {
            throw new IllegalStateException("Subscription has no items: " + subscription.getId());
        }
        SubscriptionItem item = subscription.getItems().getData().get(0);
        if (item.getPrice() == null) {
            throw new IllegalStateException("Subscription item has no Price: " + subscription.getId());
        }
        String priceId = item.getPrice().getId();
        SubscriptionPlanEntity plan = subscriptionPlanMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPlanEntity>()
                        .eq(SubscriptionPlanEntity::getStripePriceId, priceId)
                        .eq(SubscriptionPlanEntity::getIsActive, true)
                        .last("LIMIT 1"));
        if (plan == null) {
            throw new IllegalStateException("Unknown Stripe subscription Price: " + priceId);
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
            throw new IllegalStateException("Unknown add-on code: " + addonCode);
        }
        return addon;
    }

    private RechargeOrderEntity requireAddonOrderSnapshot(
            Session session,
            String clerkUserId,
            String addonCode) {
        RechargeOrderEntity order = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getStripeSessionId, session.getId())
                        .eq(RechargeOrderEntity::getOrderType, "addon")
                        .last("LIMIT 1"));
        if (order == null
                || !clerkUserId.equals(order.getClerkUserId())
                || !addonCode.equals(order.getAddonCode())
                || order.getFeatureCode() == null
                || order.getQuotaAmount() == null
                || order.getQuotaAmount() <= 0
                || order.getValidityMonthsSnapshot() == null
                || order.getValidityMonthsSnapshot() <= 0) {
            throw new IllegalStateException("Invalid or missing add-on order snapshot: " + session.getId());
        }
        if (session.getAmountTotal() != null
                && order.getPriceCents() != null
                && session.getAmountTotal().longValue() != order.getPriceCents().longValue()) {
            throw new IllegalStateException("Add-on Checkout amount does not match order: " + session.getId());
        }
        if (session.getCurrency() != null
                && order.getCurrency() != null
                && !session.getCurrency().equalsIgnoreCase(order.getCurrency())) {
            throw new IllegalStateException("Add-on Checkout currency does not match order: " + session.getId());
        }
        return order;
    }

    void refundIneligibleAddon(String paymentIntentId, String stripeSessionId) {
        if (!hasText(paymentIntentId)) {
            throw new IllegalStateException(
                    "Cannot refund ineligible add-on without PaymentIntent: " + stripeSessionId);
        }
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId)
                .putMetadata("reason", "subscription_ineligible_at_fulfillment")
                .putMetadata("checkout_session_id", stripeSessionId)
                .build();
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("addon-ineligible-refund:" + stripeSessionId)
                .build();
        try {
            Refund.create(params, options);
        } catch (StripeException e) {
            throw new IllegalStateException(
                    "Refund ineligible add-on Checkout failed: " + stripeSessionId, e);
        }
    }

    private void markAddonOrderRefunded(String stripeSessionId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getStripeSessionId, stripeSessionId)
                .eq(RechargeOrderEntity::getOrderType, "addon")
                .set(RechargeOrderEntity::getStatus, "refunded")
                .set(RechargeOrderEntity::getFailureReason, reason)
                .set(RechargeOrderEntity::getUpdatedAt, now));
    }

    private String resolveUserId(Subscription subscription) {
        String fromMetadata = subscription.getMetadata() == null
                ? null
                : subscription.getMetadata().get("clerk_user_id");
        if (fromMetadata != null && !fromMetadata.isBlank()) {
            return fromMetadata;
        }
        UserSubscriptionEntity bySubscription = userSubscriptionMapper.selectOne(
                new LambdaQueryWrapper<UserSubscriptionEntity>()
                        .eq(UserSubscriptionEntity::getStripeSubscriptionId, subscription.getId())
                        .last("LIMIT 1"));
        if (bySubscription != null) {
            return bySubscription.getClerkUserId();
        }
        UserSubscriptionEntity byCustomer = userSubscriptionMapper.selectOne(
                new LambdaQueryWrapper<UserSubscriptionEntity>()
                        .eq(UserSubscriptionEntity::getStripeCustomerId, subscription.getCustomer())
                        .last("LIMIT 1"));
        if (byCustomer != null) {
            return byCustomer.getClerkUserId();
        }
        throw new IllegalStateException("Cannot resolve user for subscription: " + subscription.getId());
    }

    private UserSubscriptionEntity findByUser(String clerkUserId) {
        return userSubscriptionMapper.selectOne(
                new LambdaQueryWrapper<UserSubscriptionEntity>()
                        .eq(UserSubscriptionEntity::getClerkUserId, clerkUserId)
                        .last("LIMIT 1"));
    }

    private BillingRobotNotifyGateway billingRobotNotifyGateway() {
        return billingRobotNotifyGatewayProvider.getIfAvailable();
    }

    private void notifyCheckoutSucceeded(
            String stripeEventId,
            String stripeEventType,
            Session session,
            Map<String, String> metadata,
            String purchaseType,
            String featureCode,
            long quotaAmount
    ) {
        BillingRobotNotifyGateway gateway = billingRobotNotifyGateway();
        if (gateway == null) {
            return;
        }
        gateway.notifyCheckoutSucceeded(BillingCheckoutNotifyRequest.builder()
                .stripeEventId(stripeEventId)
                .stripeEventType(stripeEventType)
                .sessionId(session.getId())
                .clerkUserId(firstNonBlank(metadata.get("clerk_user_id"), session.getClientReferenceId()))
                .purchaseType(purchaseType)
                .planCode(metadata.get("plan_code"))
                .targetPlanCode(metadata.get("target_plan_code"))
                .addonCode(metadata.get("addon_code"))
                .featureCode(featureCode)
                .quotaAmount(quotaAmount)
                .priceCents(session.getAmountTotal() != null ? session.getAmountTotal().intValue() : 0)
                .currency(session.getCurrency() != null ? session.getCurrency() : "usd")
                .customerEmail(session.getCustomerDetails() != null ? session.getCustomerDetails().getEmail() : null)
                .paymentIntentId(session.getPaymentIntent())
                .build());
    }

    private void notifyCheckoutExpired(
            String stripeEventId,
            String stripeEventType,
            Session session,
            Map<String, String> metadata
    ) {
        BillingRobotNotifyGateway gateway = billingRobotNotifyGateway();
        if (gateway == null) {
            return;
        }
        String purchaseType = metadata != null ? metadata.get("purchase_type") : null;
        gateway.notifyCheckoutExpired(BillingCheckoutNotifyRequest.builder()
                .stripeEventId(stripeEventId)
                .stripeEventType(stripeEventType)
                .sessionId(session.getId())
                .clerkUserId(firstNonBlank(
                        metadata != null ? metadata.get("clerk_user_id") : null,
                        session.getClientReferenceId()))
                .purchaseType(purchaseType != null ? purchaseType : "subscription")
                .planCode(metadata != null ? metadata.get("plan_code") : null)
                .targetPlanCode(metadata != null ? metadata.get("target_plan_code") : null)
                .addonCode(metadata != null ? metadata.get("addon_code") : null)
                .featureCode(resolveAnalyticsFeatureCode(metadata))
                .priceCents(session.getAmountTotal() != null ? session.getAmountTotal().intValue() : 0)
                .currency(session.getCurrency() != null ? session.getCurrency() : "usd")
                .build());
    }

    private void notifyInvoicePaymentFailed(
            String stripeEventId,
            String eventType,
            UserSubscriptionEntity entity,
            RechargeOrderEntity order,
            Invoice invoice
    ) {
        BillingRobotNotifyGateway gateway = billingRobotNotifyGateway();
        if (gateway == null) {
            return;
        }
        String clerkUserId = entity != null ? entity.getClerkUserId() : order != null ? order.getClerkUserId() : null;
        String notifyEventId = "payment_failed_" + firstNonBlank(invoice.getPaymentIntent(), invoice.getId());
        gateway.notifyPaymentFailed(BillingPaymentFailedNotifyRequest.builder()
                .notifyEventId(notifyEventId)
                .clerkUserId(clerkUserId)
                .purchaseType("subscription")
                .planCode(resolveInvoiceFailedPlanId(entity, order))
                .addonCode(order != null ? order.getAddonCode() : null)
                .priceCents(order != null && order.getPriceCents() != null
                        ? order.getPriceCents()
                        : (invoice.getAmountDue() != null ? invoice.getAmountDue().intValue() : 0))
                .currency(order != null && hasText(order.getCurrency())
                        ? order.getCurrency()
                        : (invoice.getCurrency() != null ? invoice.getCurrency() : "usd"))
                .paymentIntentId(invoice.getPaymentIntent())
                .invoiceId(invoice.getId())
                .failureReason(eventType)
                .stripeEventType(eventType)
                .build());
    }

    private BillingQuotaGateway quotaGateway() {
        BillingQuotaGateway gateway = quotaGatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new IllegalStateException("BillingQuotaGateway is not available; retry after quota service deployment");
        }
        return gateway;
    }

    private void receive(Event event) {
        StripeWebhookEventEntity existing = webhookEventMapper.selectById(event.getId());
        if (existing != null) {
            return;
        }
        StripeWebhookEventEntity entity = new StripeWebhookEventEntity();
        entity.setEventId(event.getId());
        entity.setEventType(event.getType());
        entity.setObjectId(resolveObjectId(event));
        entity.setStatus("received");
        entity.setAttemptCount(0);
        entity.setPayloadJson(com.stripe.net.ApiResource.GSON.toJson(event));
        entity.setReceivedAt(LocalDateTime.now());
        try {
            webhookEventMapper.insert(entity);
        } catch (DuplicateKeyException ignored) {
            log.debug("Stripe event already received: {}", event.getId());
        }
    }

    private boolean claim(String eventId) {
        StripeWebhookEventEntity current = webhookEventMapper.selectById(eventId);
        if (current == null
                || "succeeded".equals(current.getStatus())
                || "ignored".equals(current.getStatus())
                || "dead_letter".equals(current.getStatus())) {
            return false;
        }
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(5);
        if ("processing".equals(current.getStatus())
                && current.getProcessingStartedAt() != null
                && current.getProcessingStartedAt().isAfter(staleBefore)) {
            return false;
        }
        int updated = webhookEventMapper.update(null, new LambdaUpdateWrapper<StripeWebhookEventEntity>()
                .eq(StripeWebhookEventEntity::getEventId, eventId)
                .eq(StripeWebhookEventEntity::getStatus, current.getStatus())
                .set(StripeWebhookEventEntity::getStatus, "processing")
                .set(StripeWebhookEventEntity::getProcessingStartedAt, LocalDateTime.now())
                .set(StripeWebhookEventEntity::getLastError, null)
                .setSql("attempt_count = attempt_count + 1"));
        return updated == 1;
    }

    private void markSucceeded(String eventId) {
        webhookEventMapper.update(null, new LambdaUpdateWrapper<StripeWebhookEventEntity>()
                .eq(StripeWebhookEventEntity::getEventId, eventId)
                .set(StripeWebhookEventEntity::getStatus, "succeeded")
                .set(StripeWebhookEventEntity::getProcessedAt, LocalDateTime.now())
                .set(StripeWebhookEventEntity::getNextRetryAt, null)
                .set(StripeWebhookEventEntity::getLastError, null));
    }

    private void markFailed(String eventId, RuntimeException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (message.length() > 2000) {
            message = message.substring(0, 2000);
        }
        StripeWebhookEventEntity current = webhookEventMapper.selectById(eventId);
        int attemptCount = current == null || current.getAttemptCount() == null
                ? 1
                : Math.max(1, current.getAttemptCount());
        boolean deadLetter = attemptCount >= Math.max(1, webhookRetryMaxAttempts);
        LocalDateTime now = LocalDateTime.now();
        webhookEventMapper.update(null, new LambdaUpdateWrapper<StripeWebhookEventEntity>()
                .eq(StripeWebhookEventEntity::getEventId, eventId)
                .set(StripeWebhookEventEntity::getStatus, deadLetter ? "dead_letter" : "failed")
                .set(StripeWebhookEventEntity::getLastError, message)
                .set(StripeWebhookEventEntity::getNextRetryAt,
                        deadLetter ? null : calculateNextRetryAt(now, attemptCount))
                .set(StripeWebhookEventEntity::getDeadLetteredAt, deadLetter ? now : null)
                .set(StripeWebhookEventEntity::getProcessedAt, now));
    }

    public int retryDueEvents(int batchSize) {
        if (batchSize <= 0) {
            return 0;
        }
        List<StripeWebhookEventEntity> dueEvents = webhookEventMapper.selectList(
                new LambdaQueryWrapper<StripeWebhookEventEntity>()
                        .eq(StripeWebhookEventEntity::getStatus, "failed")
                        .le(StripeWebhookEventEntity::getNextRetryAt, LocalDateTime.now())
                        .isNotNull(StripeWebhookEventEntity::getPayloadJson)
                        .orderByAsc(StripeWebhookEventEntity::getNextRetryAt)
                        .last("LIMIT " + batchSize));
        int succeeded = 0;
        for (StripeWebhookEventEntity due : dueEvents) {
            try {
                Event event = com.stripe.net.ApiResource.GSON.fromJson(due.getPayloadJson(), Event.class);
                process(event);
                succeeded++;
            } catch (RuntimeException e) {
                log.warn("Stripe webhook internal retry failed: event={}", due.getEventId(), e);
            }
        }
        return succeeded;
    }

    static LocalDateTime calculateNextRetryAt(LocalDateTime failedAt, int attemptCount) {
        int exponent = Math.max(0, Math.min(6, attemptCount - 1));
        long delayMinutes = Math.min(60L, 1L << exponent);
        return failedAt.plusMinutes(delayMinutes);
    }

    private String resolveObjectId(Event event) {
        StripeObject object = event.getDataObjectDeserializer().getObject().orElse(null);
        if (object instanceof Session session) {
            return session.getId();
        }
        if (object instanceof Subscription subscription) {
            return subscription.getId();
        }
        if (object instanceof Invoice invoice) {
            return invoice.getId();
        }
        if (object instanceof Charge charge) {
            return charge.getId();
        }
        if (object instanceof Dispute dispute) {
            return dispute.getId();
        }
        if (object instanceof CreditNote creditNote) {
            return creditNote.getId();
        }
        return null;
    }

    private <T extends StripeObject> T resolveRequired(Event event, Class<T> type) {
        T object = resolve(event, type);
        if (object == null) {
            throw new IllegalStateException("Cannot deserialize Stripe event " + event.getId() + " as " + type.getSimpleName());
        }
        return object;
    }

    private <T extends StripeObject> T resolve(Event event, Class<T> type) {
        StripeObject object = event.getDataObjectDeserializer().getObject().orElse(null);
        if (type.isInstance(object)) {
            return type.cast(object);
        }
        try {
            object = event.getDataObjectDeserializer().deserializeUnsafe();
            if (type.isInstance(object)) {
                return type.cast(object);
            }
        } catch (Exception ignored) {
            // Fall through to raw JSON parsing for webhook API version mismatches.
        }
        String raw = event.getDataObjectDeserializer().getRawJson();
        return raw == null || raw.isBlank()
                ? null
                : StripeObject.deserializeStripeObject(raw, type, null);
    }

    String resolveInvoiceSubscriptionId(Invoice invoice) {
        if (invoice.getSubscription() != null && !invoice.getSubscription().isBlank()) {
            return invoice.getSubscription();
        }
        if (invoice.getLines() != null && invoice.getLines().getData() != null) {
            for (InvoiceLineItem line : invoice.getLines().getData()) {
                if (line.getSubscription() != null && !line.getSubscription().isBlank()) {
                    return line.getSubscription();
                }
            }
        }
        JsonObject raw = invoice.getRawJsonObject();
        String fromParent = readString(raw, "parent", "subscription_details", "subscription");
        if (fromParent != null) {
            return fromParent;
        }
        if (raw == null || !raw.has("lines") || !raw.get("lines").isJsonObject()) {
            return null;
        }
        JsonObject lines = raw.getAsJsonObject("lines");
        if (!lines.has("data") || !lines.get("data").isJsonArray()) {
            return null;
        }
        for (JsonElement item : lines.getAsJsonArray("data")) {
            if (!item.isJsonObject()) {
                continue;
            }
            String fromLineParent = readString(
                    item.getAsJsonObject(),
                    "parent",
                    "subscription_item_details",
                    "subscription");
            if (fromLineParent != null) {
                return fromLineParent;
            }
        }
        return null;
    }

    Long resolveInvoicePeriodStart(Invoice invoice) {
        return resolveInvoicePeriod(invoice, true);
    }

    Long resolveInvoicePeriodEnd(Invoice invoice) {
        return resolveInvoicePeriod(invoice, false);
    }

    private Long resolveInvoicePeriod(Invoice invoice, boolean start) {
        if (invoice.getLines() == null || invoice.getLines().getData() == null) {
            return null;
        }
        for (InvoiceLineItem line : invoice.getLines().getData()) {
            if (line.getPeriod() == null) {
                continue;
            }
            Long value = start ? line.getPeriod().getStart() : line.getPeriod().getEnd();
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    String resolveInvoiceSubscriptionId(Event event) {
        String raw = event.getDataObjectDeserializer().getRawJson();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonObject invoice = com.stripe.net.ApiResource.GSON.fromJson(raw, JsonObject.class);
        return resolveInvoiceSubscriptionId(invoice);
    }

    String resolveInvoiceIdFromInvoicePayment(Event event) {
        String raw = event.getDataObjectDeserializer().getRawJson();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonObject invoicePayment = com.stripe.net.ApiResource.GSON.fromJson(raw, JsonObject.class);
        return readString(invoicePayment, "invoice");
    }

    private String resolveInvoiceSubscriptionId(JsonObject invoice) {
        String subscription = readString(invoice, "subscription");
        if (subscription != null) {
            return subscription;
        }
        String fromParent = readString(invoice, "parent", "subscription_details", "subscription");
        if (fromParent != null) {
            return fromParent;
        }
        if (invoice == null || !invoice.has("lines") || !invoice.get("lines").isJsonObject()) {
            return null;
        }
        JsonObject lines = invoice.getAsJsonObject("lines");
        if (!lines.has("data") || !lines.get("data").isJsonArray()) {
            return null;
        }
        for (JsonElement item : lines.getAsJsonArray("data")) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject line = item.getAsJsonObject();
            String fromLine = readString(line, "subscription");
            if (fromLine != null) {
                return fromLine;
            }
            String fromLineParent = readString(line, "parent", "subscription_item_details", "subscription");
            if (fromLineParent != null) {
                return fromLineParent;
            }
        }
        return null;
    }

    private String resolveScheduleSubscriptionId(SubscriptionSchedule schedule) {
        if (schedule == null) {
            return null;
        }
        JsonObject raw = schedule.getRawJsonObject();
        String subscriptionId = readString(raw, "subscription");
        if (subscriptionId != null) {
            return subscriptionId;
        }
        return readString(raw, "released_subscription");
    }

    private Long resolveScheduleCurrentPhaseEnd(SubscriptionSchedule schedule) {
        if (schedule == null) {
            return null;
        }
        if (schedule.getCurrentPhase() != null && schedule.getCurrentPhase().getEndDate() != null) {
            return schedule.getCurrentPhase().getEndDate();
        }
        return readLong(schedule.getRawJsonObject(), "current_phase", "end_date");
    }

    Long resolveSubscriptionPeriodStart(Event event) {
        return resolveSubscriptionPeriod(event, "current_period_start");
    }

    Long resolveSubscriptionPeriodEnd(Event event) {
        return resolveSubscriptionPeriod(event, "current_period_end");
    }

    private Long resolveSubscriptionPeriod(Event event, String fieldName) {
        String raw = event.getDataObjectDeserializer().getRawJson();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonObject subscription = com.stripe.net.ApiResource.GSON.fromJson(raw, JsonObject.class);
        Long fromRoot = readLong(subscription, fieldName);
        if (fromRoot != null) {
            return fromRoot;
        }
        if (subscription == null || !subscription.has("items") || !subscription.get("items").isJsonObject()) {
            return null;
        }
        JsonObject items = subscription.getAsJsonObject("items");
        if (!items.has("data") || !items.get("data").isJsonArray() || items.getAsJsonArray("data").isEmpty()) {
            return null;
        }
        JsonElement first = items.getAsJsonArray("data").get(0);
        return first.isJsonObject() ? readLong(first.getAsJsonObject(), fieldName) : null;
    }

    private Long readLong(JsonObject object, String... path) {
        JsonElement current = object;
        for (String key : path) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            JsonObject currentObject = current.getAsJsonObject();
            if (!currentObject.has(key)) {
                return null;
            }
            current = currentObject.get(key);
        }
        if (current == null || current.isJsonNull()) {
            return null;
        }
        return current.getAsLong();
    }

    private String readString(JsonObject object, String... path) {
        JsonElement current = object;
        for (String key : path) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            JsonObject currentObject = current.getAsJsonObject();
            if (!currentObject.has(key)) {
                return null;
            }
            current = currentObject.get(key);
        }
        if (current == null || current.isJsonNull()) {
            return null;
        }
        String value = current.getAsString();
        return value == null || value.isBlank() ? null : value;
    }

    private Long readLong(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsLong();
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String resolveAnalyticsPackageType(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        String purchaseType = metadata.get("purchase_type");
        if (purchaseType == null || purchaseType.isBlank()) {
            return null;
        }
        return purchaseType.startsWith("subscription") ? "subscription" : purchaseType;
    }

    private String resolveAnalyticsPlanId(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        return firstNonBlank(
                metadata.get("target_plan_code"),
                firstNonBlank(
                        metadata.get("plan_code"),
                        firstNonBlank(
                                metadata.get("addon_code"),
                                firstNonBlank(metadata.get("pending_plan_code"), metadata.get("current_plan_code"))
                        )
                )
        );
    }

    private String resolveAnalyticsFeatureCode(Map<String, String> metadata) {
        String packageType = resolveAnalyticsPackageType(metadata);
        if ("addon".equals(packageType) && metadata != null) {
            return metadata.get("feature_code");
        }
        return "subscription";
    }

    private String resolveInvoiceFailedPlanId(
            UserSubscriptionEntity entity,
            RechargeOrderEntity order) {
        if (order != null) {
            String fromOrder = firstNonBlank(order.getTargetPlanCode(), order.getPlanCode());
            if (hasText(fromOrder)) {
                return fromOrder;
            }
        }
        if (entity == null) {
            return null;
        }
        return firstNonBlank(entity.getPendingPlanCode(), entity.getPlanCode());
    }

    private static int tierRank(String tier) {
        return switch (tier) {
            case "basic" -> 1;
            case "plus" -> 2;
            case "pro" -> 3;
            default -> 0;
        };
    }

    static Long resolvePeriodEpoch(Long override, Long fallback) {
        return override != null ? override : fallback;
    }

    private Instant instant(Long epochSecond) {
        if (epochSecond == null) {
            throw new IllegalStateException("Stripe subscription period is missing");
        }
        return Instant.ofEpochSecond(epochSecond);
    }

    private LocalDateTime fromEpoch(Long epochSecond) {
        return epochSecond == null
                ? null
                : LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC);
    }

    private String generateOrderNo() {
        return "RO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}
