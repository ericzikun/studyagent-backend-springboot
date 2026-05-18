package com.studyagent.api.dto.verla.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.state.IntentLifecycle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaConversationVO {

    private Long conversationId;
    private String userId;
    private String title;
    private String status;
    private String primaryIntent;
    /** true = 意图已识别、尚待在 Dashboard 确认卡上提交 */
    @JsonProperty("isDraft")
    private boolean draft;
    private Integer turnCount;
    private Long lastTurnId;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;

    public static VerlaConversationVO from(VerlaConversation c) {
        return VerlaConversationVO.builder()
                .conversationId(c.getId())
                .userId(c.getUserId())
                .title(c.getTitle())
                .status(c.getStatus())
                .primaryIntent(c.getPrimaryIntent())
                .draft(IntentLifecycle.conversationIsDraft(c.getIntentLifecycle()))
                .turnCount(c.getTurnCount())
                .lastTurnId(c.getLastTurnId())
                .lastMessageAt(c.getLastMessageAt())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
