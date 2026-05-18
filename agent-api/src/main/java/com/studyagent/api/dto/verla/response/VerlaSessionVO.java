package com.studyagent.api.dto.verla.response;

import com.studyagent.service.domain.verla.VerlaSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla session 对外 VO（不含 contextRefJson / resultJson 这种大块 JSON 原文）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaSessionVO {

    private Long sessionId;
    private Long conversationId;
    private Long turnId;
    private String kind;
    private String featureCode;
    private String status;
    private String correlationId;
    private Long expectedSeq;
    private Long lastEventSeq;
    private LocalDateTime lastProgressAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;

    public static VerlaSessionVO from(VerlaSession s) {
        if (s == null) {
            return null;
        }
        return VerlaSessionVO.builder()
                .sessionId(s.getId())
                .conversationId(s.getConversationId())
                .turnId(s.getTurnId())
                .kind(s.getKind())
                .featureCode(s.getFeatureCode())
                .status(s.getStatus())
                .correlationId(s.getCorrelationId())
                .expectedSeq(s.getExpectedSeq())
                .lastEventSeq(s.getLastEventSeq())
                .lastProgressAt(s.getLastProgressAt())
                .startedAt(s.getStartedAt())
                .endedAt(s.getEndedAt())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
