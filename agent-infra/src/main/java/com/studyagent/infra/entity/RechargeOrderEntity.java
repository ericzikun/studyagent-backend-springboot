package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 充值订单实体
 * 对应表 recharge_orders
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("recharge_orders")
public class RechargeOrderEntity extends BaseEntity {
    @TableField("order_no")
    private String orderNo;
    @TableField("order_type")
    private String orderType;
    @TableField("clerk_user_id")
    private String clerkUserId;
    @TableField("feature_code")
    private String featureCode;
    @TableField("package_code")
    private String packageCode;
    @TableField("plan_code")
    private String planCode;
    @TableField("target_plan_code")
    private String targetPlanCode;
    @TableField("addon_code")
    private String addonCode;
    @TableField("quota_amount")
    private Long quotaAmount;
    @TableField("validity_months_snapshot")
    private Integer validityMonthsSnapshot;
    @TableField("price_cents")
    private Integer priceCents;
    @TableField("quoted_amount_cents")
    private Integer quotedAmountCents;
    @TableField("upgrade_charge_type")
    private String upgradeChargeType;
    @TableField("currency")
    private String currency;
    @TableField("stripe_session_id")
    private String stripeSessionId;
    @TableField("stripe_payment_intent_id")
    private String stripePaymentIntentId;
    @TableField("stripe_invoice_id")
    private String stripeInvoiceId;
    @TableField("stripe_subscription_id")
    private String stripeSubscriptionId;
    @TableField("status")
    private String status;
    @TableField("failure_reason")
    private String failureReason;
    @TableField("paid_at")
    private LocalDateTime paidAt;
    @TableField("upgrade_effective_at")
    private LocalDateTime upgradeEffectiveAt;
    @TableField("switch_attempts")
    private Integer switchAttempts;
    @TableField("biz_context")
    private String bizContext;
}
