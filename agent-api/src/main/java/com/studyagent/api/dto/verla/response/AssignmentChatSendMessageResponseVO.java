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
public class AssignmentChatSendMessageResponseVO {

    private Long turnId;
    private Long userMessageId;
    private Long agentSessionId;

    public static AssignmentChatSendMessageResponseVO from(SendMessageResult result) {
        if (result == null) {
            return null;
        }
        return AssignmentChatSendMessageResponseVO.builder()
                .turnId(result.getTurnId())
                .userMessageId(result.getUserMessageId())
                .agentSessionId(result.getAgentSessionId())
                .build();
    }
}
