package com.studyagent.api.dto.verla.response;

import com.studyagent.service.application.verla.dto.FileChatPanelMessageView;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileChatMessageVO {

    private Long messageId;
    private String role;
    private String text;
    private LocalDateTime createdAt;

    public static FileChatMessageVO from(FileChatPanelMessageView view) {
        if (view == null) {
            return null;
        }
        return FileChatMessageVO.builder()
                .messageId(view.getMessageId())
                .role(view.getRole())
                .text(view.getText())
                .createdAt(view.getCreatedAt())
                .build();
    }
}
