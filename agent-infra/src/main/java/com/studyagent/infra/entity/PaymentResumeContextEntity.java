package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("payment_resume_context")
public class PaymentResumeContextEntity extends BaseEntity {

    @TableField("resume_token")
    private String resumeToken;

    @TableField("clerk_user_id")
    private String clerkUserId;

    @TableField("scene")
    private String scene;

    @TableField("resource_id")
    private String resourceId;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("status")
    private String status;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField("resumed_at")
    private LocalDateTime resumedAt;
}
