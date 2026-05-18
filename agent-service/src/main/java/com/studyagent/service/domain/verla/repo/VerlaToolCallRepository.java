package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaToolCall;

import java.util.List;

/**
 * Verla tool call trace 仓储接口（V2）。
 * <p>
 * 写路径：{@link #upsertByCallId(VerlaToolCall)} 由 {@code AGENT_TOOL_CALL_RECORDED}
 * 事件驱动，按 toolCallId 幂等。读路径：listBy* 服务于 trace 列表 / 上下文 hydrate。
 * <p>
 * 详见 docs/V2/5.1 §3 / §4 / §5。
 */
public interface VerlaToolCallRepository {

    VerlaToolCall findByCallId(String toolCallId);

    /** trace 列表（按 startedAt DESC，分页 cursor 由 service 层管理） */
    List<VerlaToolCall> listByTurn(Long turnId, int limit);

    List<VerlaToolCall> listBySession(Long sessionId, int limit);

    /** 仅返回 user-visible 的：用于 hydrate 上下文（不污染聊天历史的 internal 不要） */
    List<VerlaToolCall> listVisibleByConversation(Long conversationId, int limit);

    /**
     * V2: 按 toolCallId 幂等 upsert。
     * <ul>
     *   <li>不存在 -> insert，createdAt/updatedAt = now，缺省 status=PENDING。</li>
     *   <li>存在 -> 按"终态 > 中间态"策略合并字段：
     *     <ul>
     *       <li>status 一旦进入终态就不允许回退到非终态。</li>
     *       <li>非空字段才覆盖（避免 PARSING 后续事件清掉之前的 summary/output）。</li>
     *       <li>updatedAt 始终刷新。</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    VerlaToolCall upsertByCallId(VerlaToolCall toolCall);
}
