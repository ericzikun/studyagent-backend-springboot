package com.studyagent.api.dto.demo.aitutor;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.service.application.demo.dto.AiTutorChatDispatchResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI Tutor 一轮对话的派发回执。
 * <p>chat 端点不再是 SSE 流：命令写入事务性 outbox 后即返回，
 * 后续 {@code AITUTOR_*} 事件通过主线 SSE 通道
 * （{@code GET /v1/verla/conversations/{conversationId}/events}）推送。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTutorChatResponseVO {

    /** 主线会话 public id（{@code vc_xxx}），SSE 通道键 */
    private String conversationId;
    private String turnId;
    private String sessionId;
    private String correlationId;

    public static AiTutorChatResponseVO from(AiTutorChatDispatchResult result) {
        if (result == null) {
            return null;
        }
        return AiTutorChatResponseVO.builder()
                .conversationId(VerlaPublicIdVoSupport.conversation(result.getVerlaConversationId(), true))
                .turnId(VerlaPublicIdVoSupport.turn(result.getTurnId(), true))
                .sessionId(VerlaPublicIdVoSupport.session(result.getSessionId(), true))
                .correlationId(result.getCorrelationId())
                .build();
    }
}
