package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BillingPlan {
    private String planCode;
    private String tier;
    /** Frontend: standard_plan | basic_paid_trial */
    private String offerKind;
    private String billingInterval;
    /** Paid-trial length in days; null for standard plans. */
    private Integer trialDays;
    /** Plan code after paid-trial conversion; null for standard plans. */
    private String convertsToPlanCode;
    private String stripeProductId;
    private String stripePriceId;
    private Integer priceCents;
    private String currency;
    private Long assignmentQuota;
    private String assignmentQuotaUnit;
    private Long detectionQuota;
    private String detectionQuotaUnit;
    private Long humanizerQuota;
    private String humanizerQuotaUnit;
    private Integer maxFiles;
    private Integer maxFollowupEdits;
    private String allowedOutputTypes;

    /**
     * Legacy synthetic Free plan (pre Basic trial hard-cut). Prefer {@link #lapsedPlan()}
     * for unpaid entitlement blocking after Free pool is zeroed.
     */
    public static BillingPlan freePlan() {
        return BillingPlan.builder()
                .planCode("free")
                .tier("free")
                .offerKind(IntroTrialPlans.OFFER_KIND_STANDARD)
                .billingInterval("none")
                .assignmentQuota(1L)
                .assignmentQuotaUnit("time")
                .detectionQuota(3_000L)
                .detectionQuotaUnit("words")
                .humanizerQuota(1_000L)
                .humanizerQuotaUnit("words")
                .maxFiles(3)
                .maxFollowupEdits(3)
                .allowedOutputTypes("[\"writing\"]")
                .build();
    }

    /**
     * Unpaid hard-cut entitlements. Frontend account still exposes {@code tier=free};
     * {@code planCode=lapsed} is an internal marker only.
     * <p>
     * Quotas stay 0 (cannot run paid features). File upload keeps the legacy Free
     * cap of 3 so users can attach context before purchasing; {@code maxFollowupEdits=0}
     * still blocks follow-up edits (0 means blocked, not unlimited).
     */
    public static BillingPlan lapsedPlan() {
        return BillingPlan.builder()
                .planCode("lapsed")
                .tier("free")
                .offerKind(IntroTrialPlans.OFFER_KIND_STANDARD)
                .billingInterval("none")
                .assignmentQuota(0L)
                .assignmentQuotaUnit("time")
                .detectionQuota(0L)
                .detectionQuotaUnit("words")
                .humanizerQuota(0L)
                .humanizerQuotaUnit("words")
                .maxFiles(3)
                .maxFollowupEdits(0)
                .allowedOutputTypes("[\"writing\"]")
                .build();
    }

    public boolean isBasicPaidTrial() {
        return IntroTrialPlans.isIntroTrialOfferKind(offerKind)
                || IntroTrialPlans.isIntroTrialPlanCode(planCode);
    }

    public boolean isLapsedOrFree() {
        return "lapsed".equalsIgnoreCase(tier) || "free".equalsIgnoreCase(tier);
    }
}
