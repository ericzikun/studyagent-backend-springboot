package com.studyagent.service.domain.billing;

/**
 * Paid-trial catalog helpers.
 *
 * <ul>
 *   <li>Historical Basic Trial ({@code basic_paid_trial}): weekly subscription + Schedule
 *       conversion to Basic. Kept for webhook/order reconcile; not sellable.</li>
 *   <li>Pro Trial ({@code pro_paid_trial}): weekly US$2.99 subscription + Schedule conversion
 *       to {@code pro_monthly} / {@code pro_yearly}.</li>
 *   <li>Historical one-time Pro Trial ({@code pro_trial_once}): Mode.PAYMENT, no renew.
 *       Not sellable; expiry job may still clear leftover rows.</li>
 * </ul>
 */
public final class IntroTrialPlans {
    public static final String OFFER_KIND_STANDARD = "standard_plan";
    public static final String OFFER_KIND_BASIC_PAID_TRIAL = "basic_paid_trial";
    public static final String OFFER_KIND_PRO_PAID_TRIAL = "pro_paid_trial";

    public static final String TRIAL_PLAN_CODE_MONTHLY = "basic_trial_to_monthly";
    public static final String TRIAL_PLAN_CODE_YEARLY = "basic_trial_to_yearly";
    /** @deprecated use {@link #TRIAL_PLAN_CODE_MONTHLY} */
    public static final String TRIAL_PLAN_CODE = TRIAL_PLAN_CODE_MONTHLY;

    /** Sellable Pro Trial → Pro monthly. */
    public static final String PRO_TRIAL_PLAN_CODE_MONTHLY = "pro_trial_to_monthly";
    /** Sellable Pro Trial → Pro yearly. */
    public static final String PRO_TRIAL_PLAN_CODE_YEARLY = "pro_trial_to_yearly";
    /**
     * Default sellable Pro Trial SKU (monthly conversion).
     * @deprecated prefer {@link #PRO_TRIAL_PLAN_CODE_MONTHLY} explicitly
     */
    public static final String PRO_TRIAL_PLAN_CODE = PRO_TRIAL_PLAN_CODE_MONTHLY;

    /** Historical one-time Pro Trial (not sellable). */
    public static final String PRO_TRIAL_ONCE_PLAN_CODE = "pro_trial_once";

    public static final String CONVERSION_PLAN_CODE_MONTHLY = "basic_monthly";
    public static final String CONVERSION_PLAN_CODE_YEARLY = "basic_yearly";
    public static final String CONVERSION_PLAN_CODE = CONVERSION_PLAN_CODE_MONTHLY;

    public static final String PRO_CONVERSION_PLAN_CODE_MONTHLY = "pro_monthly";
    public static final String PRO_CONVERSION_PLAN_CODE_YEARLY = "pro_yearly";

    public static final int TRIAL_DAYS = 7;

    /** Subscription paid-trial checkout (Basic historical + Pro current). */
    public static final String PURCHASE_TYPE_INTRO_TRIAL = "subscription_intro_trial";
    public static final String ORDER_TYPE_INTRO_TRIAL = "subscription_intro_trial";

    /** Historical Pro Trial one-time checkout (Mode.PAYMENT). */
    public static final String PURCHASE_TYPE_PRO_TRIAL_ONCE = "pro_trial_once";
    public static final String ORDER_TYPE_PRO_TRIAL_ONCE = "pro_trial_once";

    public static final String STRIPE_CUSTOMER_META_INTRO_TRIAL_USED = "intro_trial_used";
    public static final String PHASE_INTRO = "intro";
    public static final String PHASE_STANDARD = "standard";
    public static final String SCHEDULE_CHANGE_TYPE_INTRO_CONVERSION = "intro_trial_conversion";

    public static final String ELIGIBILITY_ELIGIBLE = "eligible";
    public static final String ELIGIBILITY_USED = "used";
    public static final String ELIGIBILITY_ACTIVE_TRIAL = "active_trial";
    public static final String ELIGIBILITY_ACTIVE_SUBSCRIPTION = "active_subscription";
    public static final String ELIGIBILITY_UNKNOWN = "unknown";

    private IntroTrialPlans() {
    }

    public static boolean isBasicIntroTrialPlanCode(String planCode) {
        return TRIAL_PLAN_CODE_MONTHLY.equalsIgnoreCase(planCode)
                || TRIAL_PLAN_CODE_YEARLY.equalsIgnoreCase(planCode)
                || "basic_trial_weekly".equalsIgnoreCase(planCode);
    }

    public static boolean isProSubscriptionTrialPlanCode(String planCode) {
        return PRO_TRIAL_PLAN_CODE_MONTHLY.equalsIgnoreCase(planCode)
                || PRO_TRIAL_PLAN_CODE_YEARLY.equalsIgnoreCase(planCode);
    }

    public static boolean isOneTimeProTrialPlanCode(String planCode) {
        return PRO_TRIAL_ONCE_PLAN_CODE.equalsIgnoreCase(planCode);
    }

    /** Any paid-trial plan code (Basic historical, Pro subscription, or once). */
    public static boolean isIntroTrialPlanCode(String planCode) {
        return isBasicIntroTrialPlanCode(planCode)
                || isProSubscriptionTrialPlanCode(planCode)
                || isOneTimeProTrialPlanCode(planCode);
    }

    /**
     * Returns whether a paid-trial code may start a new Checkout.
     * Currently only subscription Pro Trial SKUs are sellable.
     */
    public static boolean isSellableIntroTrialPlanCode(String planCode) {
        return isProSubscriptionTrialPlanCode(planCode);
    }

    public static boolean isIntroTrialOfferKind(String offerKind) {
        return OFFER_KIND_BASIC_PAID_TRIAL.equalsIgnoreCase(offerKind)
                || OFFER_KIND_PRO_PAID_TRIAL.equalsIgnoreCase(offerKind);
    }

    public static boolean isProPaidTrialOfferKind(String offerKind) {
        return OFFER_KIND_PRO_PAID_TRIAL.equalsIgnoreCase(offerKind);
    }

    public static boolean isIntroTrialPlan(String planCode, String offerKind) {
        return isIntroTrialPlanCode(planCode) || isIntroTrialOfferKind(offerKind);
    }

    /**
     * Historical one-time Pro Trial only. Do <b>not</b> treat all {@code pro_paid_trial}
     * offer kinds as one-time — subscription Pro Trial SKUs share that offer_kind.
     */
    public static boolean isOneTimeProTrialPlan(String planCode, String offerKind) {
        return isOneTimeProTrialPlanCode(planCode);
    }

    public static boolean isBasicPaidTier(String tier) {
        return "basic".equalsIgnoreCase(tier);
    }

    public static boolean isProPaidTier(String tier) {
        return "pro".equalsIgnoreCase(tier);
    }

    /**
     * Formal plan after paid trial ends. One-time Pro Trial has no conversion target.
     */
    public static String defaultConversionPlanCode(String trialPlanCode) {
        if (isOneTimeProTrialPlanCode(trialPlanCode)) {
            return null;
        }
        if (PRO_TRIAL_PLAN_CODE_YEARLY.equalsIgnoreCase(trialPlanCode)) {
            return PRO_CONVERSION_PLAN_CODE_YEARLY;
        }
        if (PRO_TRIAL_PLAN_CODE_MONTHLY.equalsIgnoreCase(trialPlanCode)) {
            return PRO_CONVERSION_PLAN_CODE_MONTHLY;
        }
        if (TRIAL_PLAN_CODE_YEARLY.equalsIgnoreCase(trialPlanCode)) {
            return CONVERSION_PLAN_CODE_YEARLY;
        }
        return CONVERSION_PLAN_CODE_MONTHLY;
    }

    /**
     * Conversion targets must be standard paid plans, never another intro-trial SKU.
     * One-time Pro Trial has no conversion target.
     */
    public static String sanitizeConversionPlanCode(String candidate, String trialPlanCode) {
        if (isOneTimeProTrialPlanCode(trialPlanCode)) {
            return null;
        }
        if (!isIntroTrialPlanCode(candidate) && candidate != null && !candidate.isBlank()) {
            return candidate;
        }
        return defaultConversionPlanCode(trialPlanCode);
    }

    public static String conversionBillingInterval(String conversionPlanCode) {
        if (conversionPlanCode == null) {
            return null;
        }
        String code = conversionPlanCode.toLowerCase();
        if (code.endsWith("_yearly") || code.contains("year")) {
            return "year";
        }
        if (code.endsWith("_monthly") || code.contains("month")) {
            return "month";
        }
        return null;
    }

    public static int resolveTrialDays(Integer trialDays) {
        return trialDays == null || trialDays <= 0 ? TRIAL_DAYS : trialDays;
    }
}
