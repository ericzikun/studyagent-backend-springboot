package com.studyagent.infra.service.billing;

import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.entity.SubscriptionPaymentRecoveryEntity;
import com.studyagent.infra.mapper.*;
import com.studyagent.service.domain.billing.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Read-only, local facts only. Never depends on quota, billing coordination or Stripe. */
@Service
public class PaymentRecoveryAccessServiceImpl implements PaymentRecoveryAccessService {
    private static final Set<String> CLOSED = Set.of("EXPIRED", "CANCELED", "SWITCHED");
    private final UserSubscriptionMapper users;
    private final SubscriptionPaymentRecoveryMapper recoveries;
    private final SubscriptionRecoveryActionMapper actions;
    private final SubscriptionRecoveryReplacementMapper replacements;
    private final Clock clock;
    private final PaymentRecoveryProperties config;

    @Autowired
    public PaymentRecoveryAccessServiceImpl(UserSubscriptionMapper users, SubscriptionPaymentRecoveryMapper recoveries,
            SubscriptionRecoveryActionMapper actions, SubscriptionRecoveryReplacementMapper replacements, PaymentRecoveryProperties config) {
        this(users,recoveries,actions,replacements,Clock.systemUTC(),config);
    }
    public PaymentRecoveryAccessServiceImpl(UserSubscriptionMapper users, SubscriptionPaymentRecoveryMapper recoveries,
            SubscriptionRecoveryActionMapper actions, SubscriptionRecoveryReplacementMapper replacements) {
        this(users,recoveries,actions,replacements,Clock.systemUTC(),new PaymentRecoveryProperties());
    }
    PaymentRecoveryAccessServiceImpl(UserSubscriptionMapper users, SubscriptionPaymentRecoveryMapper recoveries,
            SubscriptionRecoveryActionMapper actions, SubscriptionRecoveryReplacementMapper replacements, Clock clock) {
        this(users,recoveries,actions,replacements,clock,new PaymentRecoveryProperties());
    }
    PaymentRecoveryAccessServiceImpl(UserSubscriptionMapper users, SubscriptionPaymentRecoveryMapper recoveries,
            SubscriptionRecoveryActionMapper actions, SubscriptionRecoveryReplacementMapper replacements, Clock clock, PaymentRecoveryProperties config) {
        this.users=users;this.recoveries=recoveries;this.actions=actions;this.replacements=replacements;this.clock=clock;this.config=config;
    }
    @Override public PaymentRecoveryAccess resolve(String userId, String subscriptionId) {
        return resolveInternal(userId, subscriptionId, false);
    }
    @Override public PaymentRecoveryAccess resolveForUpdate(String userId, String subscriptionId) {
        return resolveInternal(userId, subscriptionId, true);
    }
    private PaymentRecoveryAccess resolveInternal(String userId, String subscriptionId, boolean currentRead) {
        var user = currentRead ? users.selectByUserForUpdate(userId) : users.selectByUser(userId);
        if (user == null || user.getPaymentRecoveryId() == null || !Objects.equals(user.getStripeSubscriptionId(), subscriptionId))
            return PaymentRecoveryAccess.none();
        var recovery = currentRead ? recoveries.selectForUpdate(user.getPaymentRecoveryId()) : recoveries.selectById(user.getPaymentRecoveryId());
        if (recovery == null || recovery.getOwnerRecoveryId() != null || !userId.equals(recovery.getClerkUserId()))
            return PaymentRecoveryAccess.none();
        boolean sameSubscription = subscriptionId != null && subscriptionId.equals(recovery.getStripeSubscriptionId());
        boolean closed = CLOSED.contains(Objects.toString(recovery.getStatus(), "")) && recovery.getAccountClosedAt() != null;
        // Free may retain a terminal pointer for its result summary; an active different subscription never inherits it.
        if (!sameSubscription && !(subscriptionId == null && closed && "free".equals(user.getTier())))
            return PaymentRecoveryAccess.none();
        Instant start = instant(recovery.getEntitlementStartAt()), end = instant(recovery.getEntitlementEndAt());
        var selected = selectedInvoice(recovery, currentRead);
        boolean financialHold = "OPEN".equals(selected.getDisputeState()) || "LOST".equals(selected.getDisputeState()) || "FULL".equals(selected.getRefundState());
        boolean paid = !financialHold && sameSubscription && "PAID".equals(recovery.getStatus()) && "GRANTED".equals(recovery.getGrantState())
                && start != null && end != null && !clock.instant().isBefore(start) && clock.instant().isBefore(end);
        boolean locked = sameSubscription && !closed && !paid && !"PAID".equals(recovery.getStatus());
        // Confirmed paid facts awaiting a grant are held, not eligible for another payment.
        if (sameSubscription && "PAID".equals(recovery.getStatus()) && (!"GRANTED".equals(recovery.getGrantState()) || financialHold)) locked = true;
        var replacement = replacements.selectActive(recovery.getStripeSubscriptionId());
        var summary = new PaymentRecoverySummary(String.valueOf(recovery.getId()), recovery.getStatus(), locked,
                recovery.getDisplayTier(), recovery.getPlanCode(), selected.getAmountDueMinor(), selected.getAmountPaidMinor(),
                selected.getAmountRemainingMinor(), selected.getCurrency(), recovery.getFirstFailedAt(), recovery.getDeadlineAt(),
                nextRetry(recovery, replacement != null), allowedActions(recovery,locked,replacement!=null), recovery.getAnchorSyncState(), recovery.getReviewState(),
                replacement == null ? null : new PaymentRecoverySummary.Replacement(replacement.getOrderNo(), replacement.getState(), replacement.getExpiresAt()));
        return new PaymentRecoveryAccess(locked, paid, paid ? start : null, paid ? end : null, summary);
    }
    private SubscriptionPaymentRecoveryEntity selectedInvoice(SubscriptionPaymentRecoveryEntity root, boolean currentRead) {
        if (root.getEntitlementInvoiceId() == null || root.getEntitlementInvoiceId().equals(root.getStripeInvoiceId())) return root;
        var selected = currentRead ? recoveries.selectByInvoiceForUpdate(root.getEntitlementInvoiceId()) : recoveries.selectByInvoice(root.getEntitlementInvoiceId());
        if (selected == null || !root.getId().equals(selected.getOwnerRecoveryId())
                || !Objects.equals(root.getClerkUserId(), selected.getClerkUserId())
                || !Objects.equals(root.getStripeSubscriptionId(), selected.getStripeSubscriptionId()))
            throw new IllegalStateException("RECOVERY_ENTITLEMENT_SOURCE_MISMATCH:" + root.getId());
        return selected;
    }
    private List<String> allowedActions(SubscriptionPaymentRecoveryEntity r,boolean locked,boolean replacement) {
        if(!locked || !Set.of("OPEN_AUTO","OPEN_MANUAL","CLOSING","REVIEW").contains(Objects.toString(r.getStatus(),"")))return List.of();
        List<String> result=new ArrayList<>();
        boolean protectedWindow="ACTIVE".equals(r.getProtectionState());
        boolean unknownPayment=actions.selectByRecovery(r.getId()).stream().anyMatch(a->Set.of("RETRY_PAY","CREATE_CHECKOUT").contains(a.getActionType()) && Set.of("DISPATCHING","UNKNOWN","REVIEW").contains(a.getStatus()));
        boolean open=Set.of("OPEN_AUTO","OPEN_MANUAL").contains(r.getStatus()) && r.getDeadlineAt()!=null && clock.instant().isBefore(instant(r.getDeadlineAt()));
        boolean cleanReview=r.getReviewState()==null||Set.of("NONE","RESOLVED").contains(r.getReviewState());
        if(protectedWindow && open && cleanReview && !replacement && !unknownPayment && r.getAmountPaidMinor()!=null && r.getAmountPaidMinor()==0 && r.getAmountRemainingMinor()!=null && r.getAmountRemainingMinor()>0){
            if("open".equals(r.getStripeInvoiceStatus()))result.add("pay_open_invoice");
            result.add("buy_replacement");
        }
        if(protectedWindow && config.getPortalMethodOnlyConfigurationId()!=null && !config.getPortalMethodOnlyConfigurationId().isBlank())result.add("update_payment_method");
        return result;
    }
    private LocalDateTime nextRetry(SubscriptionPaymentRecoveryEntity recovery, boolean paused) {
        if (!config.isAutomaticPayEnabled() || paused || !"OPEN_AUTO".equals(recovery.getStatus()) || !"ACTIVE".equals(recovery.getProtectionState())) return null;
        var history=actions.selectByRecovery(recovery.getId());
        if(history.stream().anyMatch(a -> "RETRY_PAY".equals(a.getActionType()) && Set.of("DISPATCHING","UNKNOWN","REVIEW").contains(a.getStatus())
                || "UPDATE_METHOD".equals(a.getActionType()) && Set.of("READY","DISPATCHING","UNKNOWN","REVIEW","FAILED").contains(a.getStatus())))return null;
        return history.stream()
                .filter(a -> "RETRY_PAY".equals(a.getActionType()) && "READY".equals(a.getStatus()))
                .map(a -> a.getScheduledAt()).filter(Objects::nonNull)
                .filter(time -> instant(time).isAfter(clock.instant()))
                .filter(time -> recovery.getDeadlineAt() != null && time.isBefore(recovery.getDeadlineAt()))
                .min(LocalDateTime::compareTo).orElse(null);
    }
    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.atZone(DateTimeFormats.APP_ZONE).toInstant();
    }
}
