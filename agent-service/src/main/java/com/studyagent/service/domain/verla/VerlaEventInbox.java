package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla 事件 inbox 领域对象
 * <p>
 * 对应 verla_event_inbox 表，详见 docs/verla-Java侧MVP技术方案.md §4.4 / §11.4。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaEventInbox {

    /** 状态字面量（与 SQL CHECK 对齐） */
    public static final String STATUS_READY = "READY";
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";

    private Long id;
    private String messageId;
    private String correlationId;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    private Long eventSeq;
    private String eventType;
    private String stepId;
    private Integer stepSeq;
    private String payloadJson;
    private String status;
    private String errorMessage;
    private LocalDateTime receivedAt;
    private LocalDateTime processedAt;
}
