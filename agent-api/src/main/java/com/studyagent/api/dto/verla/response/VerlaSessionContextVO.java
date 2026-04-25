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
                .cacheHitLayer(v.getCacheHitLayer())
                .build();
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
        private Long turnId;
        private String role;
        private String text;
        private String blocksJson;
        private LocalDateTime createdAt;

        public static RecentMessageVO from(VerlaMessage m) {
            return RecentMessageVO.builder()
                    .messageId(m.getId())
                    .turnId(m.getTurnId())
                    .role(m.getRole())
                    .text(m.getTextContent())
                    .blocksJson(m.getBlocksJson())
                    .createdAt(m.getCreatedAt())
                    .build();
        }
    }
}
