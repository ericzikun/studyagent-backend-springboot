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
@TableName("subscription_payment_recovery_actions")
public class SubscriptionRecoveryActionEntity extends BaseEntity {
    @TableField("recovery_id")
    private Long recoveryId;
    @TableField("action_type")
    private String actionType;
    @TableField("action_key")
    private String actionKey;
    @TableField("target_id")
    private String targetId;
    @TableField("retry_slot")
    private Integer retrySlot;
    @TableField("scheduled_at")
    private LocalDateTime scheduledAt;
    @TableField("next_run_at")
    private LocalDateTime nextRunAt;
    @TableField("status")
    private String status;
    @TableField("payload_json")
    private String payloadJson;
    @TableField("provider_idempotency_key")
    private String providerIdempotencyKey;
    @TableField("first_dispatched_at")
    private LocalDateTime firstDispatchedAt;
    @TableField("transport_attempts")
    private Integer transportAttempts;
    @TableField(value="lease_token", updateStrategy=FieldStrategy.ALWAYS)
    private String leaseToken;
    @TableField(value="lease_until", updateStrategy=FieldStrategy.ALWAYS)
    private LocalDateTime leaseUntil;
    @TableField("provider_request_id")
    private String providerRequestId;
    @TableField("payment_intent_id")
    private String paymentIntentId;
    @TableField("charge_id")
    private String chargeId;
    @TableField("provider_message_id")
    private String providerMessageId;
    @TableField("result_json")
    private String resultJson;
    @TableField("skip_reason")
    private String skipReason;
    @TableField("last_error")
    private String lastError;
    @TableField("version")
    @Version
    private Integer version;
}
