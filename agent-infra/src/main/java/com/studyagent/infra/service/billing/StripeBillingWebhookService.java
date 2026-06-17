package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceLineItem;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.checkout.Session;
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
import com.studyagent.service.domain.billing.BillingQuotaGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeBillingWebhookService {
    private static final Set<String> SUBSCRIPTION_EVENTS = Set.of(
            "customer.subscription.created",
            "customer.subscription.updated",
            "customer.subscription.deleted",
            "invoice.paid",
            "invoice.payment_failed",
            "invoice.payment_action_required"
    );

    private final StripeWebhookEventMapper webhookEventMapper;
    private final UserSubscriptionMapper userSubscriptionMapper;
    private final SubscriptionPlanMapper subscriptionPlanMapper;
    private final AddonPackageDefMapper addonPackageDefMapper;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final ObjectProvider<BillingQuotaGateway> quotaGatewayProvider;
    private final PlatformTransactionManager transactionManager;

    public boolean supports(Event event) {
        if (SUBSCRIPTION_EVENTS.contains(event.getType())) {
            return true;
        }
        if (!event.getType().startsWith("checkout.session.")) {
            return false;
        }
        Session session = resolve(event, Session.class);
        if (session == null || session.getMetadata() == null) {
            return false;
        }
        String purchaseType = session.getMetadata().get("purchase_type");
        return "subscription".equals(purchaseType) || "addon".equals(purchaseType);
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
            case "checkout.session.completed" -> handleCheckoutCompleted(resolveRequired(event, Session.class));
            case "checkout.session.expired" -> handleCheckoutExpired(resolveRequired(event, Session.class));
            case "customer.subscription.created", "customer.subscription.updated" ->
                    syncSubscription(resolveRequired(event, Subscription.class), false, false);
            case "customer.subscription.deleted" ->
                    syncSubscription(resolveRequired(event, Subscription.class), true, false);
            case "invoice.paid" -> handleInvoicePaid(resolveRequired(event, Invoice.class), resolveInvoiceSubscriptionId(event));
            case "invoice.payment_failed", "invoice.payment_action_required" ->
                    handleInvoiceFailed(resolveRequired(event, Invoice.class), event.getType(), resolveInvoiceSubscriptionId(event));
            default -> log.info("Ignored Stripe billing event: {}", event.getType());
        }
    }

    private void handleCheckoutCompleted(Session session) {
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
            requireAddon(addonCode);
            quotaGateway().grantAddonFromCheckout(
                    clerkUserId,
                    addonCode,
                    session.getId(),
                    session.getPaymentIntent(),
                    Instant.ofEpochSecond(session.getCreated()));
            completeOrderBySession(session, addonCode, null);
            return;
        }

        if ("subscription".equals(purchaseType)) {
            updateCheckoutSubscriptionLink(session, clerkUserId, metadata.get("plan_code"));
            if (session.getSubscription() != null) {
                try {
                    syncSubscription(Subscription.retrieve(session.getSubscription()), false, false);
                } catch (StripeException e) {
                    throw new IllegalStateException("Retrieve subscription failed: " + session.getSubscription(), e);
                }
            }
        }
    }

    private void handleCheckoutExpired(Session session) {
        rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getStripeSessionId, session.getId())
                .eq(RechargeOrderEntity::getStatus, "pending")
                .set(RechargeOrderEntity::getStatus, "expired")
                .set(RechargeOrderEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private void handleInvoicePaid(Invoice invoice, String eventSubscriptionId) {
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
        boolean upgrade = pendingUpgrade != null || (existing != null
                && existing.getPlanCode() != null
                && !plan.getPlanCode().equals(existing.getPlanCode())
                && plan.getPlanCode().equals(existing.getPendingPlanCode()));

        Instant periodStart = instant(subscription.getCurrentPeriodStart());
        Instant quotaPeriodEnd = "year".equals(plan.getBillingInterval())
                ? periodStart.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()
                : instant(subscription.getCurrentPeriodEnd());
        if (upgrade) {
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
                    invoice.getId());
        }

        syncSubscription(subscription, false, true);
        if (pendingUpgrade != null) {
            completeSubscriptionOrder(pendingUpgrade, invoice, subscriptionId);
        } else if (pendingInitial != null) {
            completeSubscriptionOrder(pendingInitial, invoice, subscriptionId);
        } else {
            markInvoicePaid(invoice, subscriptionId, clerkUserId, plan, upgrade);
        }
        quotaGateway().resumeEligibleAddons(
                clerkUserId,
                subscription.getId(),
                "subscription:" + subscription.getId() + ":resume-addons:" + invoice.getId());
    }

    private void handleInvoiceFailed(Invoice invoice, String eventType, String eventSubscriptionId) {
        String subscriptionId = firstNonBlank(resolveInvoiceSubscriptionId(invoice), eventSubscriptionId);
        if (subscriptionId == null) {
            return;
        }
        UserSubscriptionEntity entity = userSubscriptionMapper.selectOne(
                new LambdaQueryWrapper<UserSubscriptionEntity>()
                        .eq(UserSubscriptionEntity::getStripeSubscriptionId, subscriptionId)
                        .last("LIMIT 1"));
        if (entity != null) {
            userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                    .eq(UserSubscriptionEntity::getId, entity.getId())
                    .set(UserSubscriptionEntity::getStatus,
                            "invoice.payment_action_required".equals(eventType) ? "incomplete" : "past_due")
                    .set(UserSubscriptionEntity::getLastSyncedAt, LocalDateTime.now())
                    .set(UserSubscriptionEntity::getUpdatedAt, LocalDateTime.now()));
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
    }

    private void syncSubscription(Subscription subscription, boolean deleted, boolean activatePendingPlan) {
        String clerkUserId = resolveUserId(subscription);
        UserSubscriptionEntity existing = findByUser(clerkUserId);
        if (deleted && existing != null
                && existing.getStripeSubscriptionId() != null
                && !subscription.getId().equals(existing.getStripeSubscriptionId())) {
            log.warn("Ignored stale subscription.deleted: user={}, current={}, event={}",
                    clerkUserId, existing.getStripeSubscriptionId(), subscription.getId());
            return;
        }

        SubscriptionPlanEntity plan = deleted ? null : requirePlanBySubscription(subscription);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            existing = new UserSubscriptionEntity();
            existing.setClerkUserId(clerkUserId);
            existing.setVersion(0);
            existing.setCreatedAt(now);
            existing.setUpdatedAt(now);
            applySubscription(existing, subscription, plan, deleted, activatePendingPlan, now);
            userSubscriptionMapper.insert(existing);
        } else {
            applySubscription(existing, subscription, plan, deleted, activatePendingPlan, now);
            updateSubscriptionEntity(existing, deleted);
        }

        if (deleted) {
            quotaGateway().clearPlanQuota(
                    clerkUserId,
                    subscription.getId(),
                    "subscription:" + subscription.getId() + ":deleted");
            quotaGateway().pauseAddons(
                    clerkUserId,
                    subscription.getId(),
                    "subscription:" + subscription.getId() + ":pause-addons");
        }
    }

    private void applySubscription(
            UserSubscriptionEntity entity,
            Subscription subscription,
            SubscriptionPlanEntity plan,
            boolean deleted,
            boolean activatePendingPlan,
            LocalDateTime now) {
        boolean pendingActivationNotPaid = !deleted
                && !activatePendingPlan
                && entity.getPendingPlanCode() != null
                && entity.getPendingPlanCode().equals(plan.getPlanCode())
                && !plan.getPlanCode().equals(entity.getPlanCode());
        entity.setStripeCustomerId(subscription.getCustomer());
        entity.setStripeSubscriptionId(subscription.getId());
        entity.setStatus(deleted ? "canceled" : subscription.getStatus());
        if (deleted) {
            entity.setStripeScheduleId(null);
        }
        if (!pendingActivationNotPaid) {
            entity.setTier(deleted ? "free" : plan.getTier());
            entity.setPlanCode(deleted ? null : plan.getPlanCode());
            entity.setCurrentPeriodStart(fromEpoch(subscription.getCurrentPeriodStart()));
            entity.setCurrentPeriodEnd(fromEpoch(subscription.getCurrentPeriodEnd()));
        }
        entity.setCancelAtPeriodEnd(!deleted && Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()));
        if (activatePendingPlan && !deleted && entity.getPendingPlanCode() != null
                && entity.getPendingPlanCode().equals(plan.getPlanCode())) {
            entity.setStripeScheduleId(null);
            entity.setPendingPlanCode(null);
            entity.setPendingEffectiveAt(null);
        }
        if (!deleted && !pendingActivationNotPaid) {
            entity.setQuotaPeriodStart(fromEpoch(subscription.getCurrentPeriodStart()));
            entity.setQuotaPeriodEnd("year".equals(plan.getBillingInterval())
                    ? fromEpoch(subscription.getCurrentPeriodStart()).plusMonths(1)
                    : fromEpoch(subscription.getCurrentPeriodEnd()));
        } else if (deleted) {
            entity.setQuotaPeriodStart(null);
            entity.setQuotaPeriodEnd(null);
        }
        entity.setLastSyncedAt(now);
        entity.setUpdatedAt(now);
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
            String planCode) {
        UserSubscriptionEntity existing = findByUser(clerkUserId);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            existing = new UserSubscriptionEntity();
            existing.setClerkUserId(clerkUserId);
            existing.setTier("free");
            existing.setPendingPlanCode(planCode);
            existing.setStatus("incomplete");
            existing.setStripeCustomerId(session.getCustomer());
            existing.setStripeSubscriptionId(session.getSubscription());
            existing.setCancelAtPeriodEnd(false);
            existing.setVersion(0);
            existing.setCreatedAt(now);
            existing.setUpdatedAt(now);
            userSubscriptionMapper.insert(existing);
        } else {
            userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                    .eq(UserSubscriptionEntity::getId, existing.getId())
                    .set(UserSubscriptionEntity::getStripeCustomerId, session.getCustomer())
                    .set(UserSubscriptionEntity::getStripeSubscriptionId, session.getSubscription())
                    .set(UserSubscriptionEntity::getPendingPlanCode, planCode)
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
                .set(RechargeOrderEntity::getStatus, "completed")
                .set(RechargeOrderEntity::getPaidAt, LocalDateTime.now())
                .set(RechargeOrderEntity::getUpdatedAt, LocalDateTime.now()));
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

    private String resolveInvoiceOrderType(Invoice invoice) {
        return "subscription_create".equals(invoice.getBillingReason())
                ? "subscription_initial"
                : "subscription_renewal";
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
        entity.setReceivedAt(LocalDateTime.now());
        try {
            webhookEventMapper.insert(entity);
        } catch (DuplicateKeyException ignored) {
            log.debug("Stripe event already received: {}", event.getId());
        }
    }

    private boolean claim(String eventId) {
        StripeWebhookEventEntity current = webhookEventMapper.selectById(eventId);
        if (current == null || "succeeded".equals(current.getStatus()) || "ignored".equals(current.getStatus())) {
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
                .set(StripeWebhookEventEntity::getLastError, null));
    }

    private void markFailed(String eventId, RuntimeException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (message.length() > 2000) {
            message = message.substring(0, 2000);
        }
        webhookEventMapper.update(null, new LambdaUpdateWrapper<StripeWebhookEventEntity>()
                .eq(StripeWebhookEventEntity::getEventId, eventId)
                .set(StripeWebhookEventEntity::getStatus, "failed")
                .set(StripeWebhookEventEntity::getLastError, message)
                .set(StripeWebhookEventEntity::getProcessedAt, LocalDateTime.now()));
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

    String resolveInvoiceSubscriptionId(Event event) {
        String raw = event.getDataObjectDeserializer().getRawJson();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonObject invoice = com.stripe.net.ApiResource.GSON.fromJson(raw, JsonObject.class);
        return resolveInvoiceSubscriptionId(invoice);
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

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
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
