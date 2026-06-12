package com.studyagent.api.dto.verla.response;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
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

    private String sessionId;
    private String conversationId;
    private String turnId;
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
        return from(s, true);
    }

    public static VerlaSessionVO fromInternal(VerlaSession s) {
        return from(s, false);
    }

    private static VerlaSessionVO from(VerlaSession s, boolean encodePublicIds) {
        if (s == null) {
            return null;
        }
        return VerlaSessionVO.builder()
                .sessionId(VerlaPublicIdVoSupport.session(s.getId(), encodePublicIds))
                .conversationId(VerlaPublicIdVoSupport.conversation(s.getConversationId(), encodePublicIds))
                .turnId(VerlaPublicIdVoSupport.turn(s.getTurnId(), encodePublicIds))
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
