package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_subscriptions")
public class UserSubscriptionEntity extends BaseEntity {
    @TableField("clerk_user_id")
    private String clerkUserId;
    private String tier;
    @TableField("plan_code")
    private String planCode;
    private String status;
    @TableField("stripe_customer_id")
    private String stripeCustomerId;
    @TableField("stripe_subscription_id")
    private String stripeSubscriptionId;
    @TableField("stripe_schedule_id")
    private String stripeScheduleId;
    @TableField("current_period_start")
    private LocalDateTime currentPeriodStart;
    @TableField("current_period_end")
    private LocalDateTime currentPeriodEnd;
    @TableField("quota_period_start")
    private LocalDateTime quotaPeriodStart;
    @TableField("quota_period_end")
    private LocalDateTime quotaPeriodEnd;
    @TableField("cancel_at_period_end")
    private Boolean cancelAtPeriodEnd;
    @TableField("pending_plan_code")
    private String pendingPlanCode;
    @TableField("pending_effective_at")
    private LocalDateTime pendingEffectiveAt;
    @TableField("pending_upgrade_order_no")
    private String pendingUpgradeOrderNo;
    @TableField("pending_upgrade_expires_at")
    private LocalDateTime pendingUpgradeExpiresAt;
    @TableField("grace_end_at")
    private LocalDateTime graceEndAt;
    @TableField("last_synced_at")
    private LocalDateTime lastSyncedAt;
    @TableField("last_stripe_event_created_at")
    private Long lastStripeEventCreatedAt;
    @TableField("last_stripe_event_id")
    private String lastStripeEventId;
    @Version
    private Integer version;
}
