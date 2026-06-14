package com.studyagent.api.dto.verla.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.studyagent.service.application.verla.dto.VerlaConversationContextView;
import com.studyagent.service.domain.verla.state.IntentLifecycle;
import com.studyagent.service.domain.verla.VerlaTurn;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Py {@code VerlaConversationContext} 对应的 Java VO；
 * 路径 {@code GET /v1/internal/verla/conversations/{cid}/context}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaConversationContextVO {

    private Long conversationId;
    private String conversationTitle;
    private String primaryIntent;
    /** true = 意图待用户在 Dashboard 上确认 */
    @JsonProperty("isDraft")
    private boolean draft;
    private String workspaceJson;
    private Long convVersion;

    private LatestTurnVO latestTurn;

    private List<VerlaSessionContextVO.RecentMessageVO> recentMessages;

    private List<VerlaArtifactVO> artifacts;

    private List<VerlaSessionContextVO.ToolSummaryVO> toolSummaries;

    private List<VerlaToolCallVO> recentToolCalls;

    private Boolean traceIncluded;

    /** 下一页消息游标（消息 id）；无更多页时为 null */
    private Long nextCursor;

    private String cacheHitLayer;

    public static VerlaConversationContextVO from(VerlaConversationContextView v) {
        if (v == null || v.getConversation() == null) {
            return null;
        }
        return VerlaConversationContextVO.builder()
                .conversationId(v.getConversation().getId())
                .conversationTitle(v.getConversation().getTitle())
                .primaryIntent(v.getConversation().getPrimaryIntent())
                .draft(IntentLifecycle.conversationIsDraft(v.getConversation().getIntentLifecycle()))
                .workspaceJson(v.getConversation().getWorkspaceJson())
                .convVersion(v.getConversation().getVersion())
                .latestTurn(LatestTurnVO.from(v.getLatestTurn()))
                .recentMessages(v.getRecentMessages() == null ? List.of()
                        : v.getRecentMessages().stream()
                            .map(VerlaSessionContextVO.RecentMessageVO::from)
                            .collect(Collectors.toList()))
                .artifacts(v.getArtifacts() == null ? List.of()
                        : v.getArtifacts().stream()
                            .map(VerlaArtifactVO::fromInternal)
                            .collect(Collectors.toList()))
                .toolSummaries(v.getToolSummaries() == null ? List.of()
                        : v.getToolSummaries().stream()
                            .map(VerlaSessionContextVO.ToolSummaryVO::from)
                            .collect(Collectors.toList()))
                .recentToolCalls(v.getRecentToolCalls() == null ? List.of()
                        : v.getRecentToolCalls().stream()
                            .map(VerlaToolCallVO::from)
                            .collect(Collectors.toList()))
                .traceIncluded(v.getTraceIncluded() != null ? v.getTraceIncluded() : Boolean.FALSE)
                .nextCursor(v.getNextCursor())
                .cacheHitLayer(v.getCacheHitLayer())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LatestTurnVO {
        private Long turnId;
        private String status;
        private String resolvedIntent;
        private String resolvedSlotsJson;

        public static LatestTurnVO from(VerlaTurn t) {
            if (t == null) {
                return null;
            }
            return LatestTurnVO.builder()
                    .turnId(t.getId())
                    .status(t.getStatus())
                    .resolvedIntent(t.getResolvedIntent())
                    .resolvedSlotsJson(t.getResolvedSlotsJson())
                    .build();
        }
    }
}
