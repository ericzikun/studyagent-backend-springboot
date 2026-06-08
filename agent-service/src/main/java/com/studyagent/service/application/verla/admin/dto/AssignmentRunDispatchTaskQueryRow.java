package com.studyagent.service.application.verla.admin.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** Flat row from assignment run monitor SQL join. */
@Data
public class AssignmentRunDispatchTaskQueryRow {
    private Long sessionId;
    private Long conversationId;
    private Long turnId;
    private String sessionStatus;
    private String featureCode;
    private String kind;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime sessionCreatedAt;
    private String clerkUserId;
    private Long outboxId;
    private Integer outboxStatus;
    private String outboxAction;
    private LocalDateTime outboxCreatedAt;
}
