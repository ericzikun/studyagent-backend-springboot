package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class SubscriptionResult {
    private String tier;
    private String planCode;
    private String status;
    private BillingAccessState accessState;
    private Boolean canConsumePaidEntitlements;
    private Boolean canRefreshPlan;
    private Boolean canPurchaseAddon;
    private List<String> availableActions;
    private Boolean isAdmin;
    /** Quota VIP：额度不扣 + 套餐权益无限，无运营后台。 */
    private Boolean isQuotaVip;
    private Integer effectiveMaxFiles;
    private Integer effectiveMaxFollowupEdits;
    private List<String> effectiveAllowedOutputTypes;
    private String stripeCustomerId;
    private String stripeSubscriptionId;
    private String stripeScheduleId;
    private LocalDateTime currentPeriodStart;
    private LocalDateTime currentPeriodEnd;
    private LocalDateTime quotaPeriodStart;
    private LocalDateTime quotaPeriodEnd;
    private Boolean cancelAtPeriodEnd;
    private String pendingPlanCode;
    private LocalDateTime pendingEffectiveAt;
    /** Nested Basic paid-trial state for frontend {@code basicTrial}. */
    private BasicTrialAccount basicTrial;
}
