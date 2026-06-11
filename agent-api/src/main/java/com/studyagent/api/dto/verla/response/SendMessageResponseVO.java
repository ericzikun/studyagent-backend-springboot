package com.studyagent.api.dto.verla.response;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
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

    private String turnId;
    private String userMessageId;
    private String planSessionId;
    private String agentSessionId;
    private String skipPlanReason;

    public static SendMessageResponseVO from(SendMessageResult r) {
        return SendMessageResponseVO.builder()
                .turnId(VerlaPublicIdVoSupport.turn(r.getTurnId(), true))
                .userMessageId(VerlaPublicIdVoSupport.message(r.getUserMessageId(), true))
                .planSessionId(VerlaPublicIdVoSupport.session(r.getPlanSessionId(), true))
                .agentSessionId(VerlaPublicIdVoSupport.session(r.getAgentSessionId(), true))
                .skipPlanReason(r.getSkipPlanReason())
                .build();
    }
}
