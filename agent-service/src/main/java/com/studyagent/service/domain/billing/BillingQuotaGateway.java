package com.studyagent.service.domain.billing;

import java.time.Instant;
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

    /** Recovery-only local operations with business time from the verified Stripe snapshot. */
    default void resetFromPaidInvoiceAt(String clerkUserId, String subscriptionId, String planCode,
            Instant periodStart, Instant periodEnd, String invoiceId, Instant businessNow) {
        throw new UnsupportedOperationException("Explicit-time plan reset is not implemented");
    }

    default void clearPlanQuotaAt(String clerkUserId, String subscriptionId, String planCode,
            String idempotencyKey, Instant businessNow) {
        throw new UnsupportedOperationException("Explicit-time plan clear is not implemented");
    }

    default void pauseAddonsAt(String clerkUserId, String subscriptionId, String idempotencyKey, Instant businessNow) {
        throw new UnsupportedOperationException("Explicit-time add-on pause is not implemented");
    }

    default void resumeEligibleAddonsAt(String clerkUserId, String subscriptionId, String idempotencyKey, Instant businessNow) {
        throw new UnsupportedOperationException("Explicit-time add-on resume is not implemented");
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
