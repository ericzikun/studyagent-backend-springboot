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
@TableName("subscription_payment_recoveries")
public class SubscriptionPaymentRecoveryEntity extends BaseEntity {
    @TableField("clerk_user_id")
    private String clerkUserId;
    @TableField("stripe_livemode")
    private Boolean stripeLivemode;
    @TableField("stripe_customer_id")
    private String stripeCustomerId;
    @TableField("stripe_subscription_id")
    private String stripeSubscriptionId;
    @TableField("stripe_invoice_id")
    private String stripeInvoiceId;
    @TableField("owner_recovery_id")
    private Long ownerRecoveryId;
    @TableField("stripe_invoice_status")
    private String stripeInvoiceStatus;
    @TableField("entitlement_invoice_id")
    private String entitlementInvoiceId;
    @TableField("plan_code")
    private String planCode;
    @TableField("display_tier")
    private String displayTier;
    @TableField("billing_interval")
    private String billingInterval;
    @TableField("interval_count")
    private Integer intervalCount;
    @TableField("contract_snapshot")
    private String contractSnapshot;
    @TableField("currency")
    private String currency;
    @TableField("amount_due_minor")
    private Long amountDueMinor;
    @TableField("amount_paid_minor")
    private Long amountPaidMinor;
    @TableField("amount_remaining_minor")
    private Long amountRemainingMinor;
    @TableField("first_failed_at")
    private LocalDateTime firstFailedAt;
    @TableField("deadline_at")
    private LocalDateTime deadlineAt;
    @TableField("status")
    private String status;
    @TableField("latest_decline_code")
    private String latestDeclineCode;
    @TableField("latest_advice_code")
    private String latestAdviceCode;
    @TableField("failure_evidence")
    private String failureEvidence;
    @TableField("last_stripe_event_created_at")
    private Long lastStripeEventCreatedAt;
    @TableField("last_stripe_event_id")
    private String lastStripeEventId;
    @TableField("protection_state")
    private String protectionState;
    @TableField("paid_at")
    private LocalDateTime paidAt;
    @TableField("payment_evidence")
    private String paymentEvidence;
    @TableField("entitlement_start_at")
    private LocalDateTime entitlementStartAt;
    @TableField("entitlement_end_at")
    private LocalDateTime entitlementEndAt;
    @TableField("quota_period_end_at")
    private LocalDateTime quotaPeriodEndAt;
    @TableField("grant_state")
    private String grantState;
    @TableField("anchor_sync_state")
    private String anchorSyncState;
    @TableField("technical_invoice_ids")
    private String technicalInvoiceIds;
    @TableField("close_reason")
    private String closeReason;
    @TableField("refunded_amount_minor")
    private Long refundedAmountMinor;
    @TableField("refund_state")
    private String refundState;
    @TableField("dispute_state")
    private String disputeState;
    @TableField("last_dispute_id")
    private String lastDisputeId;
    @TableField("account_closed_at")
    private LocalDateTime accountClosedAt;
    @TableField("review_state")
    private String reviewState;
    @TableField("review_reason")
    private String reviewReason;
    @TableField("review_assignee")
    private String reviewAssignee;
    @TableField("review_entered_at")
    private LocalDateTime reviewEnteredAt;
    @TableField("review_due_at")
    private LocalDateTime reviewDueAt;
    @TableField("review_last_progress_at")
    private LocalDateTime reviewLastProgressAt;
    @TableField("review_next_followup_at")
    private LocalDateTime reviewNextFollowupAt;
    @TableField(value="next_reconcile_at", updateStrategy=FieldStrategy.ALWAYS)
    private LocalDateTime nextReconcileAt;
    @TableField("version")
    @Version
    private Integer version;
}
