package com.studyagent.service.domain.billing;

/**
 * Constants for Basic paid-trial offers (frontend: offer_kind=basic_paid_trial).
 */
public final class IntroTrialPlans {
    public static final String OFFER_KIND_STANDARD = "standard_plan";
    public static final String OFFER_KIND_BASIC_PAID_TRIAL = "basic_paid_trial";

    public static final String TRIAL_PLAN_CODE_MONTHLY = "basic_trial_to_monthly";
    public static final String TRIAL_PLAN_CODE_YEARLY = "basic_trial_to_yearly";
    /** @deprecated use {@link #TRIAL_PLAN_CODE_MONTHLY} */
    public static final String TRIAL_PLAN_CODE = TRIAL_PLAN_CODE_MONTHLY;

    public static final String CONVERSION_PLAN_CODE_MONTHLY = "basic_monthly";
    public static final String CONVERSION_PLAN_CODE_YEARLY = "basic_yearly";
    public static final String CONVERSION_PLAN_CODE = CONVERSION_PLAN_CODE_MONTHLY;

    public static final int TRIAL_DAYS = 7;

    public static final String PURCHASE_TYPE_INTRO_TRIAL = "subscription_intro_trial";
    public static final String ORDER_TYPE_INTRO_TRIAL = "subscription_intro_trial";
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

    public static boolean isIntroTrialPlanCode(String planCode) {
        return TRIAL_PLAN_CODE_MONTHLY.equalsIgnoreCase(planCode)
                || TRIAL_PLAN_CODE_YEARLY.equalsIgnoreCase(planCode)
                || "basic_trial_weekly".equalsIgnoreCase(planCode);
    }

    /**
     * Returns whether an intro-trial code may be used for a new Checkout.
     *
     * <p>Historical yearly/weekly codes remain recognizable so existing Stripe
     * subscriptions, orders, and webhook events can still be reconciled.</p>
     */
    public static boolean isSellableIntroTrialPlanCode(String planCode) {
        return TRIAL_PLAN_CODE_MONTHLY.equalsIgnoreCase(planCode);
    }

    public static boolean isIntroTrialOfferKind(String offerKind) {
        return OFFER_KIND_BASIC_PAID_TRIAL.equalsIgnoreCase(offerKind);
    }

    public static boolean isIntroTrialPlan(String planCode, String offerKind) {
        return isIntroTrialPlanCode(planCode) || isIntroTrialOfferKind(offerKind);
    }

    public static boolean isBasicPaidTier(String tier) {
        return "basic".equalsIgnoreCase(tier);
    }

    public static String defaultConversionPlanCode(String trialPlanCode) {
        if (TRIAL_PLAN_CODE_YEARLY.equalsIgnoreCase(trialPlanCode)) {
            return CONVERSION_PLAN_CODE_YEARLY;
        }
        return CONVERSION_PLAN_CODE_MONTHLY;
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
