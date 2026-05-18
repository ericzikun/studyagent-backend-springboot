package com.studyagent.api.dto.verla.response;

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

    private Long turnId;
    private Long conversationId;
    private Long userMessageId;
    private String status;
    private String resolvedIntent;
    private Long activeSessionId;
    private Long planSessionId;
    private Long agentSessionId;
    private Integer totalSteps;
    private Integer completedSteps;
    private LocalDateTime lastProgressAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;

    public static VerlaTurnVO from(VerlaTurn t) {
        if (t == null) {
            return null;
        }
        return VerlaTurnVO.builder()
                .turnId(t.getId())
                .conversationId(t.getConversationId())
                .userMessageId(t.getUserMessageId())
                .status(t.getStatus())
                .resolvedIntent(t.getResolvedIntent())
                .activeSessionId(t.getActiveSessionId())
                .planSessionId(t.getPlanSessionId())
                .agentSessionId(t.getAgentSessionId())
                .totalSteps(t.getTotalSteps())
                .completedSteps(t.getCompletedSteps())
                .lastProgressAt(t.getLastProgressAt())
                .startedAt(t.getStartedAt())
                .endedAt(t.getEndedAt())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
