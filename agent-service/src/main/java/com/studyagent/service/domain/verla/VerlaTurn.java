package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla Turn 领域对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaTurn {

    private Long id;
    private Long conversationId;
    private Long userMessageId;
    /** CREATED / PLANNING / AWAITING_CLARIFY / DISPATCHING / RUNNING_AGENT / COMPLETED / FAILED / CANCELLING / CANCELLED */
    private String status;
    private String resolvedIntent;
    private String resolvedSlotsJson;
    private Long activeSessionId;
    private Long planSessionId;
    private Long agentSessionId;
    private Integer totalSteps;
    private Integer completedSteps;
    private LocalDateTime lastProgressAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String errorJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
