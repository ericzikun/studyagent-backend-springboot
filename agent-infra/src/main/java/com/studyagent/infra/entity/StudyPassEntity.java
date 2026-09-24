package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 一次购买的题库通行证。同一用户可能有多行历史记录，只有
 * {@code status='active'} 且 {@code expires_at} 未过期的一行代表当前有效权益。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("study_passes")
public class StudyPassEntity extends BaseEntity {
    @TableField("clerk_user_id")
    private String clerkUserId;
    @TableField("stripe_subscription_id")
    private String stripeSubscriptionId;
    @TableField("stripe_price_id")
    private String stripePriceId;
    @TableField("purchase_key")
    private String purchaseKey;
    @TableField("cancel_at_period_end")
    private Boolean cancelAtPeriodEnd;
    @TableField("last_paid_invoice_id")
    private String lastPaidInvoiceId;
    @TableField("renewal_price_cents")
    private Integer renewalPriceCents;
    @TableField("renewal_currency")
    private String renewalCurrency;
    @TableField("billing_interval_days")
    private Integer billingIntervalDays;

    @TableField("pass_code")
    private String passCode;
    private String status;
    @TableField("started_at")
    private LocalDateTime startedAt;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("order_id")
    private Long orderId;
    @TableField("stripe_checkout_session_id")
    private String stripeCheckoutSessionId;
    @TableField("stripe_payment_intent_id")
    private String stripePaymentIntentId;
    @TableField("upgrade_credit_used_at")
    private LocalDateTime upgradeCreditUsedAt;
    /** 为该通行证抵扣创建的 Stripe 优惠券，复用以避免重复创建。 */
    @TableField("upgrade_credit_coupon_id")
    private String upgradeCreditCouponId;
}
