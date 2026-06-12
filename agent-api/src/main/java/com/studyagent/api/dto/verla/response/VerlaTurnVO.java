package com.studyagent.api.dto.verla.response;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.service.domain.verla.VerlaTurn;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla turn 对外 VO（隐藏内部 errorJson 等敏感字段）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaTurnVO {

    private String turnId;
    private String conversationId;
    private String userMessageId;
    private String status;
    private String resolvedIntent;
    private String activeSessionId;
    private String planSessionId;
    private String agentSessionId;
    private Integer totalSteps;
    private Integer completedSteps;
    private LocalDateTime lastProgressAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;

    public static VerlaTurnVO from(VerlaTurn t) {
        return from(t, true);
    }

    public static VerlaTurnVO fromInternal(VerlaTurn t) {
        return from(t, false);
    }

    private static VerlaTurnVO from(VerlaTurn t, boolean encodePublicIds) {
        if (t == null) {
            return null;
        }
        return VerlaTurnVO.builder()
                .turnId(VerlaPublicIdVoSupport.turn(t.getId(), encodePublicIds))
                .conversationId(VerlaPublicIdVoSupport.conversation(t.getConversationId(), encodePublicIds))
                .userMessageId(VerlaPublicIdVoSupport.message(t.getUserMessageId(), encodePublicIds))
                .status(t.getStatus())
                .resolvedIntent(t.getResolvedIntent())
                .activeSessionId(VerlaPublicIdVoSupport.session(t.getActiveSessionId(), encodePublicIds))
                .planSessionId(VerlaPublicIdVoSupport.session(t.getPlanSessionId(), encodePublicIds))
                .agentSessionId(VerlaPublicIdVoSupport.session(t.getAgentSessionId(), encodePublicIds))
                .totalSteps(t.getTotalSteps())
                .completedSteps(t.getCompletedSteps())
                .lastProgressAt(t.getLastProgressAt())
                .startedAt(t.getStartedAt())
                .endedAt(t.getEndedAt())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
