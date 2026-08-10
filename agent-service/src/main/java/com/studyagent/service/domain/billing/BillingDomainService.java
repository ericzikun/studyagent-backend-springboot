package com.studyagent.service.domain.billing;

import com.studyagent.service.domain.payment.CheckoutSessionResult;

public interface BillingDomainService {
    BillingCatalogResult getCatalog();

    CheckoutSessionResult createSubscriptionCheckout(
            String clerkUserId,
            String customerEmail,
            String planCode,
            String successUrl,
            String cancelUrl,
            String resumeToken
    );

    CheckoutSessionResult createAddonCheckout(
            String clerkUserId,
            String customerEmail,
            String addonCode,
            String successUrl,
            String cancelUrl,
            String resumeToken
    );

    BillingPortalSessionResult createBillingPortalSession(String clerkUserId, String returnUrl);

    BillingRecordPageResult getBillingRecords(String clerkUserId, String cursor, Integer limit);

    BillingHostedInvoiceResult createBillingHostedInvoice(String clerkUserId, String recordId);

    SubscriptionResult getCurrentSubscription(String clerkUserId);

    SubscriptionResult cancelAtPeriodEnd(String clerkUserId);

    SubscriptionResult resumeSubscription(String clerkUserId);

    SubscriptionResult changeSubscription(String clerkUserId, String targetPlanCode);

    SubscriptionResult downgradeSubscription(String clerkUserId, String targetPlanCode);

    BillingPlan getEffectivePlanOrFree(String clerkUserId);

    boolean isPaidMember(String clerkUserId);

    /**
     * After paid-trial Checkout is paid (Basic historical or Pro subscription trial):
     * mark once-per-customer usage and attach a Subscription Schedule that converts the
     * 7-day intro into the formal plan ({@code basic_*} or {@code pro_*}).
     */
    void fulfillIntroTrialSubscription(String clerkUserId, String stripeCustomerId, String stripeSubscriptionId);

    /**
     * Historical: after one-time Pro Trial Checkout ({@code pro_trial_once}) is paid.
     * New Pro Trial sales use {@link #fulfillIntroTrialSubscription} instead.
     */
    void fulfillProTrialPayment(
            String clerkUserId,
            String stripeCustomerId,
            String stripeSessionId,
            String stripePaymentIntentId,
            String planCode);
}
