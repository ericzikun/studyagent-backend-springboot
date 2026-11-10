package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * One Simulation Lab pass purchase. Multiple rows per user are history; only an
 * active row whose {@code expires_at} is in the future grants access.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("simulation_lab_passes")
public class SimulationPassEntity extends BaseEntity {
    @TableField("clerk_user_id")
    private String clerkUserId;
    @TableField("pass_code")
    private String passCode;
    private String source;
    private String status;
    @TableField("started_at")
    private LocalDateTime startedAt;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("stripe_checkout_session_id")
    private String stripeCheckoutSessionId;
    @TableField("stripe_payment_intent_id")
    private String stripePaymentIntentId;
    @TableField("amount_cents")
    private Integer amountCents;
    private String currency;
}
