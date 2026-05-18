package com.studyagent.api.dto.verla.response;

import com.studyagent.service.application.verla.dto.SendMessageResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageResponseVO {

    private Long turnId;
    private Long userMessageId;
    private Long planSessionId;
    private Long agentSessionId;
    private String skipPlanReason;

    public static SendMessageResponseVO from(SendMessageResult r) {
        return SendMessageResponseVO.builder()
                .turnId(r.getTurnId())
                .userMessageId(r.getUserMessageId())
                .planSessionId(r.getPlanSessionId())
                .agentSessionId(r.getAgentSessionId())
                .skipPlanReason(r.getSkipPlanReason())
                .build();
    }
}
