package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("subscription_payment_recovery_replacements")
public class SubscriptionRecoveryReplacementEntity extends BaseEntity {
    @TableField("recovery_id")
    private Long recoveryId;
    @TableField("clerk_user_id")
    private String clerkUserId;
    @TableField("old_subscription_id")
    private String oldSubscriptionId;
    @TableField("order_no")
    private String orderNo;
    @TableField("target_plan_code")
    private String targetPlanCode;
    @TableField("state")
    private String state;
    @TableField(value="active_subscription_key", updateStrategy=FieldStrategy.ALWAYS)
    private String activeSubscriptionKey;
    @TableField("checkout_session_id")
    private String checkoutSessionId;
    @TableField("new_subscription_id")
    private String newSubscriptionId;
    @TableField("paused_at")
    private LocalDateTime pausedAt;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("closed_at")
    private LocalDateTime closedAt;
    @TableField("unknown_since")
    private LocalDateTime unknownSince;
    @TableField("quote_snapshot")
    private String quoteSnapshot;
    @TableField("version")
    @Version
    private Integer version;
}
