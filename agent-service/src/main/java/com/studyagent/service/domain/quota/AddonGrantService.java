package com.studyagent.service.domain.quota;

import java.time.Instant;

public interface AddonGrantService {
    void grantFromPaidCheckout(
            String clerkUserId,
            String addonCode,
            String stripeSessionId,
            String paymentIntentId,
            Instant paidAt
    );

    default void grantFromPaidCheckout(
            String clerkUserId,
            AddonGrantSnapshot snapshot,
            String stripeSessionId,
            String paymentIntentId,
            Instant paidAt
    ) {
        grantFromPaidCheckout(
                clerkUserId,
                snapshot.addonCode(),
                stripeSessionId,
                paymentIntentId,
                paidAt);
    }

    void expireEligible(String clerkUserId, String featureCode, String trigger);

    void pauseAll(String clerkUserId, String subscriptionId, String idempotencyKey);

    void resumeEligible(String clerkUserId, String subscriptionId, String idempotencyKey);

    /** Local lifecycle entry point; businessNow must be supplied by the verified billing snapshot. */
    default void pauseAllAt(String clerkUserId, String subscriptionId, String idempotencyKey, Instant businessNow) {
        throw new UnsupportedOperationException("Explicit-time add-on pause is not implemented");
    }

    /** Local lifecycle entry point; never resolves Stripe/Test Clock time. */
    default void resumeEligibleAt(String clerkUserId, String subscriptionId, String idempotencyKey, Instant businessNow) {
        throw new UnsupportedOperationException("Explicit-time add-on resume is not implemented");
    }

    default void adjustForRefund(
            String paymentIntentId,
            String adjustmentId,
            long cumulativeRefundCents,
            long originalPaymentCents) {
        throw new UnsupportedOperationException("Add-on refund adjustments are not implemented");
    }

    default void freezeForDispute(String paymentIntentId, String disputeId) {
        throw new UnsupportedOperationException("Add-on dispute freezing is not implemented");
    }

    default void restoreAfterDispute(String paymentIntentId, String disputeId) {
        throw new UnsupportedOperationException("Add-on dispute restoration is not implemented");
    }
}
