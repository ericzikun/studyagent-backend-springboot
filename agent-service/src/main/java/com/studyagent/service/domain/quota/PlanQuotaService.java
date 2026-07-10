package com.studyagent.service.domain.quota;

import java.time.Instant;

public interface PlanQuotaService {
    /**
     * Idempotently refreshes plan quota state for one feature before a balance read or consume attempt.
     */
    void refreshPlanQuotaIfNeeded(String clerkUserId, String featureCode);

    /**
     * Idempotently refreshes all plan-backed feature quotas for one user in a single subscription/plan lookup.
     */
    void refreshAllPlanQuotasIfNeeded(String clerkUserId);

    void resetFromPaidInvoice(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant quotaPeriodStart,
            Instant quotaPeriodEnd,
            String invoiceId
    );

    /**
     * Source-compatible overload for callers that can classify the paid invoice.
     * Implementations that only override the legacy six-argument method receive this call
     * without {@code grantType}; analytics-aware implementations must override this overload.
     */
    default void resetFromPaidInvoice(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant quotaPeriodStart,
            Instant quotaPeriodEnd,
            String invoiceId,
            String grantType
    ) {
        resetFromPaidInvoice(
                clerkUserId, subscriptionId, planCode, quotaPeriodStart, quotaPeriodEnd, invoiceId);
    }

    void addFullPlanForUpgrade(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant quotaPeriodStart,
            Instant quotaPeriodEnd,
            String invoiceId
    );

    void grantUpgradeFromCheckout(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant quotaPeriodStart,
            Instant quotaPeriodEnd,
            String upgradeOrderNo
    );

    void clearPlanQuota(String clerkUserId, String subscriptionId, String planCode, String idempotencyKey);
}
