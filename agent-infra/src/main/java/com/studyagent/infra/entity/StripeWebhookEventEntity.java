package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("stripe_webhook_events")
public class StripeWebhookEventEntity {
    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;
    @TableField("event_type")
    private String eventType;
    @TableField("object_id")
    private String objectId;
    private String status;
    @TableField("attempt_count")
    private Integer attemptCount;
    @TableField("last_error")
    private String lastError;
    @TableField("received_at")
    private LocalDateTime receivedAt;
    @TableField("processing_started_at")
    private LocalDateTime processingStartedAt;
    @TableField("processed_at")
    private LocalDateTime processedAt;
}
