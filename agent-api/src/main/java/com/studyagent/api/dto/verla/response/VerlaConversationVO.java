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
    /** 改动时间：用户点击任务 / 编辑内容 / 发送消息时刷新（Recent Task 排序键） */
    private LocalDateTime lastActiveAt;
    private LocalDateTime createdAt;
    /** 编辑器预览图列表，按固定 kind 顺序排列 (document / code / slides) */
    private List<EditorPreviewItem> editorPreviews;
    /** 该 assignment conversation 可展示的产物类型集合（document / slides / code），
     *  由后端根据 artifacts 实时推导，不依赖截图链路。
     *  非 assignment conversation 返回空数组或不返回该字段。 */
    private List<String> artifactPreviewKinds;

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
                .lastActiveAt(c.getLastActiveAt())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
