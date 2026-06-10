package com.studyagent.api.dto.verla.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
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

    /** 对外 public id，格式 vc_{sqids} */
    private String conversationId;
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
    private String lastTurnId;
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
        return fromPublic(c);
    }

    /** 用户面 API：返回带类型前缀的 public id */
    public static VerlaConversationVO fromPublic(VerlaConversation c) {
        return from(c, null, true);
    }

    /** 内部 API（Python）：保留数字字符串，便于服务间解析 */
    public static VerlaConversationVO fromInternal(VerlaConversation c) {
        return from(c, null, false);
    }

    public static VerlaConversationVO from(VerlaConversation c, String dashboardStatus) {
        return from(c, dashboardStatus, true);
    }

    private static VerlaConversationVO from(
            VerlaConversation c, String dashboardStatus, boolean encodePublicIds) {
        return VerlaConversationVO.builder()
                .conversationId(VerlaPublicIdVoSupport.conversation(c.getId(), encodePublicIds))
                .userId(c.getUserId())
                .title(c.getTitle())
                .status(c.getStatus())
                .dashboardStatus(dashboardStatus)
                .primaryIntent(c.getPrimaryIntent())
                .draft(IntentLifecycle.conversationIsDraft(c.getIntentLifecycle()))
                .turnCount(c.getTurnCount())
                .lastTurnId(VerlaPublicIdVoSupport.turn(c.getLastTurnId(), encodePublicIds))
                .lastMessageAt(c.getLastMessageAt())
                .lastActiveAt(c.getLastActiveAt())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
