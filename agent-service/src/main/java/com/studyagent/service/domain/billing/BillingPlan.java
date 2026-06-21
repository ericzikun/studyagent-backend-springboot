package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BillingPlan {
    private String planCode;
    private String tier;
    private String billingInterval;
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

    public static BillingPlan freePlan() {
        return BillingPlan.builder()
                .planCode("free")
                .tier("free")
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
}
