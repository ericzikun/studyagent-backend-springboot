package com.studyagent.service.domain.billing;

import java.time.Instant;
import java.time.LocalDateTime;
import com.studyagent.service.domain.quota.AddonGrantSnapshot;

/**
 * Integration contract implemented by the quota owner.
 * Stripe handlers deliberately fail and retry when this gateway is unavailable.
 */
public interface BillingQuotaGateway {
    void resetFromPaidInvoice(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant periodStart,
            Instant periodEnd,
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
            Instant periodStart,
            Instant periodEnd,
            String invoiceId,
            String grantType
    ) {
        resetFromPaidInvoice(
                clerkUserId, subscriptionId, planCode, periodStart, periodEnd, invoiceId);
    }

    void addFullPlanForUpgrade(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant periodStart,
            Instant periodEnd,
            String invoiceId
    );

    void grantUpgradeFromCheckout(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant periodStart,
            Instant periodEnd,
            String upgradeOrderNo
    );

    void clearPlanQuota(String clerkUserId, String subscriptionId, String planCode, String idempotencyKey);

    /**
     * Clear with a period snapshot captured before the subscription row was normalized.
     * Used by subscription.deleted, where the stored quota window is already null.
     */
    default void clearPlanQuota(String clerkUserId, String subscriptionId, String planCode,
            String idempotencyKey, LocalDateTime periodStart, LocalDateTime periodEnd,
            String periodTimeZone) {
        clearPlanQuota(clerkUserId, subscriptionId, planCode, idempotencyKey);
    }

    void grantAddonFromCheckout(
            String clerkUserId,
            String addonCode,
            String stripeSessionId,
            String paymentIntentId,
            Instant paidAt
    );

    default void grantAddonFromCheckout(
            String clerkUserId,
            AddonGrantSnapshot snapshot,
            String stripeSessionId,
            String paymentIntentId,
            Instant paidAt
    ) {
        grantAddonFromCheckout(
                clerkUserId,
                snapshot.addonCode(),
                stripeSessionId,
                paymentIntentId,
                paidAt);
    }

    void pauseAddons(String clerkUserId, String subscriptionId, String idempotencyKey);

    void resumeEligibleAddons(String clerkUserId, String subscriptionId, String idempotencyKey);

    default void adjustAddonForRefund(
            String paymentIntentId,
            String adjustmentId,
            long cumulativeRefundCents,
            long originalPaymentCents) {
        throw new UnsupportedOperationException("Add-on refund adjustments are not implemented");
    }

    default void freezeAddonForDispute(String paymentIntentId, String disputeId) {
        throw new UnsupportedOperationException("Add-on dispute freezing is not implemented");
    }

    default void restoreAddonAfterDispute(String paymentIntentId, String disputeId) {
        throw new UnsupportedOperationException("Add-on dispute restoration is not implemented");
    }
}
