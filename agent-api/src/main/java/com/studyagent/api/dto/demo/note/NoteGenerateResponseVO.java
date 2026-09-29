package com.studyagent.api.dto.demo.note;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.service.application.demo.dto.NoteGenerateDispatchResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 生成笔记的派发回执。
 * <p>端点不是 SSE 流：命令写入事务性 outbox 后即返回，后续 {@code NOTE_*} 事件通过主线 SSE
 * 通道（{@code GET /v1/verla/conversations/{conversationId}/events}）推送。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteGenerateResponseVO {

    /** 主线会话 public id（{@code vc_xxx}），SSE 通道键。 */
    private String conversationId;
    private String turnId;
    private String sessionId;
    private String correlationId;

    public static NoteGenerateResponseVO from(NoteGenerateDispatchResult result) {
        if (result == null) {
            return null;
        }
        return NoteGenerateResponseVO.builder()
                .conversationId(VerlaPublicIdVoSupport.conversation(result.getVerlaConversationId(), true))
                .turnId(VerlaPublicIdVoSupport.turn(result.getTurnId(), true))
                .sessionId(VerlaPublicIdVoSupport.session(result.getSessionId(), true))
                .correlationId(result.getCorrelationId())
                .build();
    }
}
