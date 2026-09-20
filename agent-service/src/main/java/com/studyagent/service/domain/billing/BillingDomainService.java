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

    /**
     * 创建题库通行证的一次性 Stripe Checkout。
     *
     * <p>通行证与订阅互不相关：不要求用户是付费会员，也不读写
     * {@code user_subscriptions}。仅当该用户当前没有未过期的通行证时才允许下单。
     */
    CheckoutSessionResult createStudyPassCheckout(
            String clerkUserId,
            String customerEmail,
            String passCode,
            String successUrl,
            String cancelUrl,
            String resumeToken
    );

    /**
     * 题库通行证 Checkout 支付成功后的落库：写入一条 30 天有效期的通行证并生成
     * {@code order_type='study_pass'} 的账单记录。重复投递同一 session 不产生第二条记录。
     * 返回 false 表示其他会话已发放有效权益，调用者必须幂等退款而非再次发放。
     */
    boolean fulfillStudyPassPayment(
            String clerkUserId,
            String passCode,
            String stripeSessionId,
            String stripePaymentIntentId
    );

    /**
     * 会员订阅支付成功后核销该通行证的抵扣资格。幂等；通行证已过期时不追溯。
     */
    void consumeStudyPassUpgradeCredit(Long studyPassId);

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
