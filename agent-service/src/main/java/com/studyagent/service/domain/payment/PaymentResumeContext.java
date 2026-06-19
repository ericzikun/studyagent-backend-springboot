package com.studyagent.service.domain.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResumeContext {

    private Long id;
    private String resumeToken;
    private String clerkUserId;
    private String scene;
    private String resourceId;
    private String idempotencyKey;
    private String payloadJson;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime resumedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
