package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla Session 领域对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaSession {

    private Long id;
    private Long conversationId;
    private Long turnId;
    /** PLAN / AGENT / MATERIALS */
    private String kind;
    private String featureCode;
    /** CREATED / DISPATCHING / RUNNING / SUCCEEDED / FAILED / CANCELLING / CANCELLED */
    private String status;
    private String correlationId;
    private String contextRefJson;
    private String resultJson;
    private String errorJson;
    private Long expectedSeq;
    private Long lastEventSeq;
    private LocalDateTime lastProgressAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
