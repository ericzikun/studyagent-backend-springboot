package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("billing_entitlement_fulfillments")
public class BillingEntitlementFulfillmentEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("payment_key")
    private String paymentKey;
    @TableField("source_type")
    private String sourceType;
    @TableField("source_id")
    private String sourceId;
    @TableField("source_event_id")
    private String sourceEventId;
    @TableField("recharge_order_id")
    private Long rechargeOrderId;
    @TableField("purchase_type")
    private String purchaseType;
    @TableField("product_code")
    private String productCode;
    @TableField("payment_status")
    private String paymentStatus;
    @TableField("fulfillment_status")
    private String fulfillmentStatus;
    @TableField("payment_accepted_at")
    private LocalDateTime paymentAcceptedAt;
    @TableField("fulfillment_started_at")
    private LocalDateTime fulfillmentStartedAt;
    @TableField("fulfilled_at")
    private LocalDateTime fulfilledAt;
    @TableField("last_error_code")
    private String lastErrorCode;
    @TableField("last_error_message")
    private String lastErrorMessage;
    @TableField("last_error_at")
    private LocalDateTime lastErrorAt;
    @TableField("attempt_count")
    private Integer attemptCount;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
