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

    /**
     * One-off remediation for subscriptions that converted out of the retired Pro Trial yearly SKU
     * and therefore renew as an annual plan instead of the intended monthly plan.
     * <p>
     * Voids any unpaid annual invoice, then either switches the subscription to Pro monthly
     * immediately (charged right away, billing cycle restarted) or — when that annual invoice was
     * already paid — schedules the same monthly switch for the end of the paid annual term. A
     * subscription that already carries a scheduled cancellation is only voided, so the user's
     * cancellation intent is respected.
     */
    ConvertedYearlyTrialMigrationOutcome migrateConvertedYearlyTrialToMonthly(String clerkUserId);
}
