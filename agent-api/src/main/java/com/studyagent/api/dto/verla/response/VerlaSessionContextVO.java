package com.studyagent.api.dto.verla.response;

import com.studyagent.service.application.verla.dto.VerlaSessionContextView;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Verla session 启动上下文 VO（暴露给 Py 的 /v1/internal/verla/sessions/{sid}/context 响应）
 * <p>
 * 字段命名与 docs §10 / §22 对齐，Py 端按这些字段名做反序列化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaSessionContextVO {

    private Long sessionId;
    private String sessionKind;
    private String sessionStatus;

    private Long turnId;
    private String turnStatus;
    private String resolvedIntent;
    private String resolvedSlotsJson;

    private Long conversationId;
    private String conversationTitle;
    private String primaryIntent;
    private String workspaceJson;
    private Long convVersion;

    private List<UpstreamSessionVO> upstreamSessions;
    private List<RecentMessageVO> recentMessages;

    /** V2：conversation 级 artifact（{@code includeArtifacts=false} 时空数组） */
    private List<VerlaArtifactVO> artifacts;

    /** V2：USER_VISIBLE 工具摘要（{@code includeToolSummaries=true}） */
    private List<ToolSummaryVO> toolSummaries;

    /** V2：当前 session tool trace（{@code includeTrace=true}） */
    private List<VerlaToolCallVO> recentToolCalls;

    /** V2：是否包含完整 trace（与请求 {@code includeTrace} 一致） */
    private Boolean traceIncluded;

    /** 命中缓存层标记：none / sess / turn / conv */
    private String cacheHitLayer;

    public static VerlaSessionContextVO from(VerlaSessionContextView v) {
        return VerlaSessionContextVO.builder()
                .sessionId(v.getSession().getId())
                .sessionKind(v.getSession().getKind())
                .sessionStatus(v.getSession().getStatus())
                .turnId(v.getTurn().getId())
                .turnStatus(v.getTurn().getStatus())
                .resolvedIntent(v.getTurn().getResolvedIntent())
                .resolvedSlotsJson(v.getTurn().getResolvedSlotsJson())
                .conversationId(v.getConversation().getId())
                .conversationTitle(v.getConversation().getTitle())
                .primaryIntent(v.getConversation().getPrimaryIntent())
                .workspaceJson(v.getConversation().getWorkspaceJson())
                .convVersion(v.getConversation().getVersion())
                .upstreamSessions(v.getUpstreamSessions() == null ? List.of()
                        : v.getUpstreamSessions().stream()
                            .map(UpstreamSessionVO::from)
                            .collect(Collectors.toList()))
                .recentMessages(v.getRecentMessages() == null ? List.of()
                        : v.getRecentMessages().stream()
                            .map(RecentMessageVO::from)
                            .collect(Collectors.toList()))
                .artifacts(v.getArtifacts() == null ? List.of()
                        : v.getArtifacts().stream()
                            .map(VerlaArtifactVO::from)
                            .collect(Collectors.toList()))
                .toolSummaries(v.getToolSummaries() == null ? List.of()
                        : v.getToolSummaries().stream()
                            .map(ToolSummaryVO::from)
                            .collect(Collectors.toList()))
                .recentToolCalls(v.getRecentToolCalls() == null ? List.of()
                        : v.getRecentToolCalls().stream()
                            .map(VerlaToolCallVO::from)
                            .collect(Collectors.toList()))
                .traceIncluded(v.getTraceIncluded() != null ? v.getTraceIncluded() : Boolean.FALSE)
                .cacheHitLayer(v.getCacheHitLayer())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolSummaryVO {
        private String toolCallId;
        private String agentName;
        private String toolName;
        private String summary;
        private String status;
        private String visibility;

        public static ToolSummaryVO from(VerlaSessionContextView.ToolCallSummaryView s) {
            if (s == null) {
                return null;
            }
            return ToolSummaryVO.builder()
                    .toolCallId(s.getToolCallId())
                    .agentName(s.getAgentName())
                    .toolName(s.getToolName())
                    .summary(s.getSummary())
                    .status(s.getStatus())
                    .visibility(s.getVisibility())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpstreamSessionVO {
        private Long sessionId;
        private String kind;
        private String status;
        private String resultJson;

        public static UpstreamSessionVO from(VerlaSession s) {
            return UpstreamSessionVO.builder()
                    .sessionId(s.getId())
                    .kind(s.getKind())
                    .status(s.getStatus())
                    .resultJson(s.getResultJson())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentMessageVO {
        private Long messageId;
        private Long conversationId;
        private Long turnId;
        private Long sourceSessionId;
        private String role;
        private String text;
        private String blocksJson;
        private String attachmentsJson;
        private String metaJson;
        private LocalDateTime createdAt;

        public static RecentMessageVO from(VerlaMessage m) {
            return RecentMessageVO.builder()
                    .messageId(m.getId())
                    .conversationId(m.getConversationId())
                    .turnId(m.getTurnId())
                    .sourceSessionId(m.getSourceSessionId())
                    .role(m.getRole())
                    .text(m.getTextContent())
                    .blocksJson(VerlaBlocksJsonSanitizer.withoutTopLevelStage(m.getBlocksJson()))
                    .attachmentsJson(m.getAttachmentsJson())
                    .metaJson(m.getMetaJson())
                    .createdAt(m.getCreatedAt())
                    .build();
        }
    }
}
