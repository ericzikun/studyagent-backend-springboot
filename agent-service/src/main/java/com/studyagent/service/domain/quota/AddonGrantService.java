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
