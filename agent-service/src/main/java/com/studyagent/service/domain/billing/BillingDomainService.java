package com.studyagent.service.domain.billing;

import com.studyagent.service.domain.payment.CheckoutSessionResult;

public interface BillingDomainService {
    BillingCatalogResult getCatalog();

    CheckoutSessionResult createSubscriptionCheckout(
            String clerkUserId,
            String customerEmail,
            String planCode
    );

    CheckoutSessionResult createAddonCheckout(
            String clerkUserId,
            String customerEmail,
            String addonCode
    );

    SubscriptionResult getCurrentSubscription(String clerkUserId);

    SubscriptionResult cancelAtPeriodEnd(String clerkUserId);

    SubscriptionResult resumeSubscription(String clerkUserId);

    SubscriptionResult changeSubscription(String clerkUserId, String targetPlanCode);

    SubscriptionResult upgradeSubscription(String clerkUserId, String targetPlanCode);

    SubscriptionResult downgradeSubscription(String clerkUserId, String targetPlanCode);

    BillingPlan getEffectivePlanOrFree(String clerkUserId);

    boolean isPaidMember(String clerkUserId);
}
