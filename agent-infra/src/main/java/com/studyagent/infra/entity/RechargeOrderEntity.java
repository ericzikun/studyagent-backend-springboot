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
    @TableField("clerk_user_id")
    private String clerkUserId;
    @TableField("feature_code")
    private String featureCode;
    @TableField("package_code")
    private String packageCode;
    @TableField("quota_amount")
    private Long quotaAmount;
    @TableField("price_cents")
    private Integer priceCents;
    @TableField("currency")
    private String currency;
    @TableField("stripe_session_id")
    private String stripeSessionId;
    @TableField("stripe_payment_intent_id")
    private String stripePaymentIntentId;
    @TableField("status")
    private String status;
    @TableField("failure_reason")
    private String failureReason;
    @TableField("paid_at")
    private LocalDateTime paidAt;
}
