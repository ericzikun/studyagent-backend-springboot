package com.studyagent.api.dto.demo.note;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.service.domain.demo.note.NoteConversation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 笔记会话响应体。
 * <p>{@code id} 是 demo 自己的主键（前端 REST 路径继续用它），
 * {@code verlaConversationId} 是主线 public id（{@code vc_xxx}），用于建立 SSE 事件通道。
 * 刷新页面时 {@code noteMd} 就是已生成的笔记正文（未生成为 null）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteConversationVO {

    private Long id;
    private String verlaConversationId;
    private String title;
    private String sourceType;
    private String attachmentObjectId;
    private String noteMd;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static NoteConversationVO from(NoteConversation c) {
        if (c == null) {
            return null;
        }
        return NoteConversationVO.builder()
                .id(c.getId())
                .verlaConversationId(VerlaPublicIdVoSupport.conversation(c.getVerlaConversationId(), true))
                .title(c.getTitle())
                .sourceType(c.getSourceType())
                .attachmentObjectId(c.getAttachmentObjectId())
                .noteMd(c.getNoteMd())
                .status(c.getStatus())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
