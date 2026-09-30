package com.studyagent.api.dto.demo.note;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.service.domain.demo.note.NoteConversation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 历史列表条目。
 * <p>刻意不带 {@code noteMd}：列表可能有几十条、每条笔记正文可达数十 KB，
 * 快照端点才返回全文（见 {@link NoteConversationVO}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteConversationSummaryVO {

    private Long id;
    /** 主线 public id（{@code vc_xxx}），前端跳转与 SSE 通道键。 */
    private String verlaConversationId;
    private String title;
    private String sourceType;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static NoteConversationSummaryVO from(NoteConversation c) {
        if (c == null) {
            return null;
        }
        return NoteConversationSummaryVO.builder()
                .id(c.getId())
                .verlaConversationId(VerlaPublicIdVoSupport.conversation(c.getVerlaConversationId(), true))
                .title(c.getTitle())
                .sourceType(c.getSourceType())
                .status(c.getStatus())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
