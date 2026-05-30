package com.studyagent.api.dto.verla.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.state.IntentLifecycle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaConversationVO {

    private Long conversationId;
    private String userId;
    private String title;
    private String status;
    /** Dashboard 历史卡片任务展示状态：progressing / needs-choice / completed / failed。 */
    private String dashboardStatus;
    private String primaryIntent;
    /** true = 意图已识别、尚待在 Dashboard 确认卡上提交 */
    @JsonProperty("isDraft")
    private boolean draft;
    private Integer turnCount;
    private Long lastTurnId;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    /** 编辑器预览图列表，按固定 kind 顺序排列 (document / code / slides) */
    private List<EditorPreviewItem> editorPreviews;

    public static VerlaConversationVO from(VerlaConversation c) {
        return from(c, null);
    }

    public static VerlaConversationVO from(VerlaConversation c, String dashboardStatus) {
        return VerlaConversationVO.builder()
                .conversationId(c.getId())
                .userId(c.getUserId())
                .title(c.getTitle())
                .status(c.getStatus())
                .dashboardStatus(dashboardStatus)
                .primaryIntent(c.getPrimaryIntent())
                .draft(IntentLifecycle.conversationIsDraft(c.getIntentLifecycle()))
                .turnCount(c.getTurnCount())
                .lastTurnId(c.getLastTurnId())
                .lastMessageAt(c.getLastMessageAt())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
