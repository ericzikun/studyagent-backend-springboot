package com.studyagent.api.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.service.application.verla.sse.VerlaSseEventPayload;

import java.util.Map;

/**
 * Public SSE boundary shared by live delivery and replay. Internal routing keeps
 * Long IDs; resource IDs use the same codec as REST. The event cursor is not a
 * resource ID and must remain numeric for ordering and Last-Event-ID recovery.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerlaSseEventVO(
        Long id,
        String type,
        String conversationId,
        String turnId,
        String sessionId,
        String stepId,
        Integer stepSeq,
        Map<String, Object> payload) {

    public static VerlaSseEventVO from(VerlaSseEventPayload event) {
        return new VerlaSseEventVO(
                event.getId(), event.getType(),
                VerlaPublicIdVoSupport.conversation(event.getConversationId(), true),
                VerlaPublicIdVoSupport.turn(event.getTurnId(), true),
                VerlaPublicIdVoSupport.session(event.getSessionId(), true),
                event.getStepId(), event.getStepSeq(), event.getPayload());
    }
}
