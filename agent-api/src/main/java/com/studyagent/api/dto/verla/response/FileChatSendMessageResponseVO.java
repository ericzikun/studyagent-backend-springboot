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
public class FileChatSendMessageResponseVO {

    private String turnId;
    private String userMessageId;
    private String agentSessionId;

    public static FileChatSendMessageResponseVO from(SendMessageResult result) {
        if (result == null) {
            return null;
        }
        return FileChatSendMessageResponseVO.builder()
                .turnId(VerlaPublicIdVoSupport.turn(result.getTurnId(), true))
                .userMessageId(VerlaPublicIdVoSupport.message(result.getUserMessageId(), true))
                .agentSessionId(VerlaPublicIdVoSupport.session(result.getAgentSessionId(), true))
                .build();
    }
}
