package com.studyagent.api.dto.demo.note;

import com.studyagent.service.domain.demo.note.NoteMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 对话栏的一条消息。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteMessageVO {

    private Long id;
    /** user / assistant。 */
    private String role;
    private String contentMd;
    /** 会话内递增序号（前端据此稳定排序 / 去重）。 */
    private Integer seq;
    private LocalDateTime createdAt;

    public static NoteMessageVO from(NoteMessage m) {
        if (m == null) {
            return null;
        }
        return NoteMessageVO.builder()
                .id(m.getId())
                .role(m.getRole())
                .contentMd(m.getContentMd())
                .seq(m.getSeq())
                .createdAt(m.getCreatedAt())
                .build();
    }
}
