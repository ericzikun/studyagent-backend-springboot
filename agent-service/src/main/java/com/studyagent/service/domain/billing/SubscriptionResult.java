package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SubscriptionResult {
    private String tier;
    private String planCode;
    private String status;
    private String stripeCustomerId;
    private String stripeSubscriptionId;
    private LocalDateTime currentPeriodStart;
    private LocalDateTime currentPeriodEnd;
    private LocalDateTime quotaPeriodStart;
    private LocalDateTime quotaPeriodEnd;
    private Boolean cancelAtPeriodEnd;
    private String pendingPlanCode;
    private LocalDateTime pendingEffectiveAt;
}
