package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("subscription_plans")
public class SubscriptionPlanEntity extends BaseEntity {
    @TableField("plan_code")
    private String planCode;
    private String tier;
    @TableField("billing_interval")
    private String billingInterval;
    @TableField("offer_kind")
    private String offerKind;
    @TableField("trial_days")
    private Integer trialDays;
    @TableField("converts_to_plan_code")
    private String convertsToPlanCode;
    @TableField("stripe_product_id")
    private String stripeProductId;
    @TableField("stripe_price_id")
    private String stripePriceId;
    @TableField("price_cents")
    private Integer priceCents;
    private String currency;
    @TableField("assignment_quota")
    private Long assignmentQuota;
    @TableField("detection_quota")
    private Long detectionQuota;
    @TableField("humanizer_quota")
    private Long humanizerQuota;
    @TableField("max_files")
    private Integer maxFiles;
    @TableField("max_followup_edits")
    private Integer maxFollowupEdits;
    @TableField("allowed_output_types")
    private String allowedOutputTypes;
    @TableField("config_version")
    private Integer configVersion;
    @TableField("is_active")
    private Boolean isActive;
    @TableField("display_order")
    private Integer displayOrder;
}
