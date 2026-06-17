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
    private Long detectionQuota;
    private Long humanizerQuota;
    private Integer maxFiles;
    private Integer maxFollowupEdits;
    private String allowedOutputTypes;

    public static BillingPlan freePlan() {
        return BillingPlan.builder()
                .planCode("free")
                .tier("free")
                .billingInterval("none")
                .assignmentQuota(1L)
                .detectionQuota(1L)
                .humanizerQuota(1L)
                .maxFiles(3)
                .maxFollowupEdits(3)
                .allowedOutputTypes("[\"writing\"]")
                .build();
    }
}
