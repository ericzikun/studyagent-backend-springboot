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

    void expireEligible(String clerkUserId, String featureCode, String trigger);

    void pauseAll(String clerkUserId, String subscriptionId, String idempotencyKey);

    void resumeEligible(String clerkUserId, String subscriptionId, String idempotencyKey);
}
