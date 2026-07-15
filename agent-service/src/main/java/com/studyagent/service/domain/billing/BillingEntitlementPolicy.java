package com.studyagent.service.domain.billing;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Single policy boundary for translating subscription lifecycle into entitlement decisions.
 */
public final class BillingEntitlementPolicy {
    private BillingEntitlementPolicy() {
    }

    public static BillingAccessState resolveAccessState(
            String status,
            boolean cancelAtPeriodEnd,
            LocalDateTime graceEndAt,
            LocalDateTime now) {
        String normalized = normalize(status);
        if ("active".equals(normalized) || "trialing".equals(normalized)) {
            return cancelAtPeriodEnd ? BillingAccessState.ACTIVE_ENDING : BillingAccessState.ACTIVE;
        }
        if ("past_due".equals(normalized)) {
            return graceEndAt != null && now != null && now.isBefore(graceEndAt)
                    ? BillingAccessState.GRACE
                    : BillingAccessState.SUSPENDED;
        }
        if ("incomplete".equals(normalized)) {
            return BillingAccessState.PAYMENT_PENDING;
        }
        if ("unpaid".equals(normalized) || "paused".equals(normalized)) {
            return BillingAccessState.SUSPENDED;
        }
        return BillingAccessState.TERMINATED;
    }

    public static boolean allowsPlanRefresh(String status) {
        String normalized = normalize(status);
        return "active".equals(normalized) || "trialing".equals(normalized);
    }

    public static boolean allowsPaidEntitlementConsumption(
            String status,
            LocalDateTime graceEndAt,
            LocalDateTime now) {
        BillingAccessState state = resolveAccessState(status, false, graceEndAt, now);
        return state == BillingAccessState.ACTIVE || state == BillingAccessState.GRACE;
    }

    public static boolean allowsAddonPurchase(String status) {
        return allowsPlanRefresh(status);
    }

    private static String normalize(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }
}
