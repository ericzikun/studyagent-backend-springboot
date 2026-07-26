package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_addon_grants")
public class UserAddonGrantEntity extends BaseEntity {
    @TableField("clerk_user_id")
    private String clerkUserId;
    @TableField("feature_code")
    private String featureCode;
    @TableField("grant_type")
    private String grantType;
    @TableField("addon_code")
    private String addonCode;
    private String status;
    @TableField("initial_amount")
    private Long initialAmount;
    @TableField("remaining_amount")
    private Long remainingAmount;
    @TableField("reversed_amount")
    private Long reversedAmount;
    @TableField("quota_debt_amount")
    private Long quotaDebtAmount;
    @TableField("pre_dispute_status")
    private String preDisputeStatus;
    @TableField("stripe_session_id")
    private String stripeSessionId;
    @TableField("stripe_payment_intent_id")
    private String stripePaymentIntentId;
    @TableField("source_order_id")
    private Long sourceOrderId;
    @TableField("migration_key")
    private String migrationKey;
    @TableField("purchased_at")
    private LocalDateTime purchasedAt;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("paused_at")
    private LocalDateTime pausedAt;
    @Version
    @TableField("version")
    private Integer version;
}
