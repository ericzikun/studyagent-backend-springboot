package com.studyagent.infra.service.simulation;

import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.BillingPlan;
import com.studyagent.service.domain.billing.PaymentRecoveryAccess;
import com.studyagent.service.domain.billing.PaymentRecoveryAccessService;
import com.studyagent.service.domain.billing.SubscriptionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Resolves whether a user may create a Simulation Lab and how many labs the
 * current membership period still allows.
 *
 * <p>Order matters: admin/membership/pass take precedence over the one-time
 * free trial. Membership uses the plan's assignment quota as its per-period
 * count; usage is counted from successful SIMULATION sessions, so failed runs
 * do not consume a slot. The free trial itself is tracked by
 * {@link SimulationTrialGrantService} so historical labs do not consume the
 * post-launch regrant.
 */
@Service
@RequiredArgsConstructor
public class SimulationEntitlementService {

    public static final String ENTITLEMENT_ADMIN = "admin";
    public static final String ENTITLEMENT_PASS = "pass";
    public static final String ENTITLEMENT_MEMBERSHIP = "membership";
    public static final String ENTITLEMENT_FREE_TRIAL = "free_trial";
    public static final String ENTITLEMENT_FREE_TRIAL_USED = "free_trial_used";
    /** Subscription payment recovery owns this account: paid access stays locked. */
    public static final String ENTITLEMENT_PAYMENT_RECOVERY = "payment_recovery";

    private final BillingDomainService billingDomainService;
    private final SimulationPassService simulationPassService;
    private final SimulationTrialGrantService simulationTrialGrantService;
    private final PaymentRecoveryAccessService paymentRecoveryAccessService;
    private final JdbcTemplate jdbcTemplate;

    public record Entitlement(
            boolean canCreate,
            boolean previewLocked,
            String entitlement,
            boolean unlimited,
            Long limit,
            long used,
            Long remaining,
            LocalDateTime periodStart,
            LocalDateTime periodEnd,
            boolean freeTrialAvailable,
            boolean freeTrialUsed,
            boolean membershipIncluded,
            boolean admin,
            boolean passActive,
            LocalDateTime passExpiresAt,
            boolean purchasable) {
    }

    public Entitlement resolve(String clerkUserId) {
        boolean member = isPaidMember(clerkUserId);
        simulationTrialGrantService.ensureGrant(clerkUserId);
        SimulationPassService.Access passAccess =
                simulationPassService.access(clerkUserId, member);
        boolean passActive = simulationPassService.hasActivePass(clerkUserId);

        if (passAccess.admin()) {
            return new Entitlement(true, false, ENTITLEMENT_ADMIN, true,
                    null, 0, null, null, null,
                    false, true, false, true, false, null, passAccess.purchasable());
        }
        // Payment recovery outranks membership and pass: paid access stays locked
        // until the outstanding invoice is settled, and no new pass may be sold.
        if (paymentRecoveryLocked(clerkUserId)) {
            return new Entitlement(false, true, ENTITLEMENT_PAYMENT_RECOVERY, false,
                    0L, 0L, 0L, null, null,
                    false, false, false, false, passActive, passAccess.expiresAt(), false);
        }
        // Membership supersedes a legacy pass: a pass-exhausted user who buys a
        // membership must unlock immediately, without waiting for the pass to expire.
        if (member) {
            BillingPlan plan = billingDomainService.getEffectivePlanOrFree(clerkUserId);
            long limit = plan == null || plan.getAssignmentQuota() == null
                    ? 0L : Math.max(0L, plan.getAssignmentQuota());
            SubscriptionResult subscription = billingDomainService.getCurrentSubscription(clerkUserId);
            LocalDateTime periodStart = firstNonNull(
                    subscription == null ? null : subscription.getQuotaPeriodStart(),
                    subscription == null ? null : subscription.getCurrentPeriodStart());
            if (periodStart == null) {
                periodStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
            }
            LocalDateTime periodEnd = firstNonNull(
                    subscription == null ? null : subscription.getQuotaPeriodEnd(),
                    subscription == null ? null : subscription.getCurrentPeriodEnd());
            if (periodEnd == null) {
                periodEnd = periodStart.plusMonths(1);
            }
            long used = countSucceeded(clerkUserId, periodStart, periodEnd);
            long remaining = Math.max(0L, limit - used);
            return new Entitlement(remaining > 0, false, ENTITLEMENT_MEMBERSHIP, false,
                    limit, used, remaining, periodStart, periodEnd,
                    false, false, true, false, false, null, false);
        }
        if (passActive) {
            LocalDateTime passEnd = passAccess.expiresAt();
            LocalDateTime passStart = simulationPassService.activePassStartedAt(clerkUserId);
            if (passStart == null) {
                passStart = passEnd.minusDays(7);
            }
            long limit = SimulationPassService.PASS_MAX_GENERATIONS;
            long used = countSucceeded(clerkUserId, passStart, passEnd);
            long remaining = Math.max(0L, limit - used);
            return new Entitlement(remaining > 0, false, ENTITLEMENT_PASS, false,
                    limit, used, remaining, passStart, passEnd,
                    false, false, false, false, true, passEnd,
                    passAccess.purchasable());
        }

        boolean everGenerated = simulationTrialGrantService.isUsed(clerkUserId);
        if (everGenerated) {
            return new Entitlement(false, true, ENTITLEMENT_FREE_TRIAL_USED, false,
                    1L, 1L, 0L, null, null,
                    false, true, false, false, false, null, passAccess.purchasable());
        }
        return new Entitlement(true, false, ENTITLEMENT_FREE_TRIAL, false,
                1L, 0L, 1L, null, null,
                true, false, false, false, false, null, passAccess.purchasable());
    }

    private boolean isPaidMember(String clerkUserId) {
        if (billingDomainService.isPaidMember(clerkUserId)) {
            return true;
        }
        SubscriptionResult subscription = billingDomainService.getCurrentSubscription(clerkUserId);
        return subscription != null
                && Boolean.TRUE.equals(subscription.getCanConsumePaidEntitlements());
    }

    /** Read-only recovery lock; admin and quota VIP keep their independent grant. */
    private boolean paymentRecoveryLocked(String clerkUserId) {
        if (paymentRecoveryAccessService == null) {
            return false;
        }
        SubscriptionResult account = billingDomainService.getCurrentSubscription(clerkUserId);
        PaymentRecoveryAccess access = paymentRecoveryAccessService.resolve(
                clerkUserId, account == null ? null : account.getStripeSubscriptionId());
        return access != null && access.locked();
    }

    private long countSucceeded(String clerkUserId, LocalDateTime start, LocalDateTime end) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM verla_sessions s "
                        + "INNER JOIN verla_conversations c ON c.id = s.conversation_id "
                        + "WHERE c.user_id = ? AND c.status <> 'deleted' "
                        + "AND s.kind = 'SIMULATION' AND s.status = 'SUCCEEDED'");
        if (start != null) {
            sql.append(" AND s.created_at >= ?");
        }
        if (end != null) {
            sql.append(" AND s.created_at < ?");
        }
        Long count;
        if (start != null && end != null) {
            count = jdbcTemplate.queryForObject(sql.toString(), Long.class, clerkUserId, start, end);
        } else if (start != null) {
            count = jdbcTemplate.queryForObject(sql.toString(), Long.class, clerkUserId, start);
        } else {
            count = jdbcTemplate.queryForObject(sql.toString(), Long.class, clerkUserId);
        }
        return count == null ? 0L : count;
    }

    private static LocalDateTime firstNonNull(LocalDateTime first, LocalDateTime second) {
        return Objects.requireNonNullElse(first, second);
    }
}
