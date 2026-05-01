package com.studyagent.api.dto.verla.response;

import com.studyagent.service.domain.verla.VerlaToolCall;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla tool call trace VO（Context API {@code includeTrace=true} 时返回）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaToolCallVO {

    private Long id;
    private String toolCallId;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    private String stepId;
    private String parentCallId;
    private String agentName;
    private String toolName;
    private String status;
    private String visibility;
    private String toolInputJson;
    private String toolOutputJson;
    private String summary;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer durationMs;
    private String metaJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static VerlaToolCallVO from(VerlaToolCall c) {
        if (c == null) {
            return null;
        }
        return VerlaToolCallVO.builder()
                .id(c.getId())
                .toolCallId(c.getToolCallId())
                .conversationId(c.getConversationId())
                .turnId(c.getTurnId())
                .sessionId(c.getSessionId())
                .stepId(c.getStepId())
                .parentCallId(c.getParentCallId())
                .agentName(c.getAgentName())
                .toolName(c.getToolName())
                .status(c.getStatus())
                .visibility(c.getVisibility())
                .toolInputJson(c.getToolInputJson())
                .toolOutputJson(c.getToolOutputJson())
                .summary(c.getSummary())
                .errorCode(c.getErrorCode())
                .errorMessage(c.getErrorMessage())
                .startedAt(c.getStartedAt())
                .finishedAt(c.getFinishedAt())
                .durationMs(c.getDurationMs())
                .metaJson(c.getMetaJson())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
