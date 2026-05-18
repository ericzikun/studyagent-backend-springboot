package com.studyagent.service.application.verla.dto;

import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaToolCall;
import com.studyagent.service.domain.verla.VerlaTurn;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Verla session 启动时一把拿全的上下文聚合视图（V2 扩展）
 * <p>
 * 对应 docs/verla-Java侧MVP技术方案.md §10.1 / §10.2 与
 * docs/V2/Verla Message 上下文与 Tool Trace 设计方案.md §6。
 * <ul>
 *     <li>conversation 摘要 + 最近 N 条消息（N 由 Context API {@code messageLimit} 控制）</li>
 *     <li>当前 session 自身实体</li>
 *     <li>当前 turn 实体</li>
 *     <li>同 turn 内已 SUCCEEDED 的兄弟 session（一般是 plan→agent 的接力）</li>
 *     <li>V2：artifacts / toolSummaries / recentToolCalls / traceIncluded</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaSessionContextView {

    private VerlaConversation conversation;
    private VerlaTurn turn;
    private VerlaSession session;

    /** 同 turn 内已完成的兄弟 session（按时间升序） */
    private List<VerlaSession> upstreamSessions;

    /** conversation 最近 N 条消息（按 created_at desc） */
    private List<VerlaMessage> recentMessages;

    /** V2：conversation 级 artifact（可选；可能被服务端截断条数） */
    private List<VerlaArtifact> artifacts;

    /** V2：USER_VISIBLE 工具摘要（{@code includeToolSummaries=true}） */
    private List<ToolCallSummaryView> toolSummaries;

    /** V2：当前 session 最近 tool 调用完整记录（{@code includeTrace=true}） */
    private List<VerlaToolCall> recentToolCalls;

    /** V2：是否与请求参数 {@code includeTrace=true} 一致（Py 判断 hydrate 模式） */
    private Boolean traceIncluded;

    /** 命中缓存层标记（none / sess / turn / conv），便于 metrics 与调试 */
    private String cacheHitLayer;

    /**
     * 压缩工具摘要（hydrate 用，不等同于 DB 全字段）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallSummaryView {
        private String toolCallId;
        private String agentName;
        private String toolName;
        private String summary;
        private String status;
        private String visibility;
    }
}
