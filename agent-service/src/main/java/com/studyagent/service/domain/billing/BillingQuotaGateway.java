package com.studyagent.service.domain.billing;

import java.time.Instant;

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

    void clearPlanQuota(String clerkUserId, String subscriptionId, String idempotencyKey);

    void grantAddonFromCheckout(
            String clerkUserId,
            String addonCode,
            String stripeSessionId,
            String paymentIntentId,
            Instant paidAt
    );

    void pauseAddons(String clerkUserId, String subscriptionId, String idempotencyKey);

    void resumeEligibleAddons(String clerkUserId, String subscriptionId, String idempotencyKey);
}
