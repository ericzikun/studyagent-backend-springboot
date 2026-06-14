package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaEventInbox;

import java.util.List;
import java.util.Map;

/**
 * Verla 事件 inbox 仓储接口
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §8。
 */
public interface VerlaEventInboxRepository {

    /**
     * 尝试插入；message_id 唯一冲突时返回 false（幂等）。
     */
    boolean tryInsert(VerlaEventInbox row);

    VerlaEventInbox findByMessageId(String messageId);

    /**
     * 找一行 status=READY 且 event_seq=expectedSeq 的记录（用于按序 drain）。
     */
    VerlaEventInbox findReady(Long sessionId, Long expectedSeq);

    int markProcessed(Long id);

    int markSkipped(Long id, String reason);

    int markFailed(Long id, String reason);

    /**
     * 找出"下一期望 seq 已在 inbox 中存在但未推进"的 session id 列表（兜底 drain 用）
     */
    List<Long> findStuckSessions(int limit);

    /**
     * SSE 断线补发：取该 conversation 中 status=PROCESSED 且 id > afterId 的事件，按 id 升序。
     * <p>
     * 详见 docs/verla-Java侧MVP技术方案.md §13.3。
     */
    List<VerlaEventInbox> findReplayByConversation(Long conversationId, Long afterId, int limit);

    /**
     * Dashboard 状态快照：取某个 conversation 最近处理完成的 Py/SSE 事件，按 id 倒序。
     */
    List<VerlaEventInbox> findRecentProcessedByConversation(Long conversationId, int limit);

    /**
     * Dashboard 批量状态：一次查询多个 conversation 的最近 PROCESSED 事件。
     */
    Map<Long, List<VerlaEventInbox>> findRecentProcessedByConversationIds(
            List<Long> conversationIds, int limitPerConversation);

    /**
     * 某 session 最近一条已处理事件（运维看板用）。
     */
    VerlaEventInbox findLatestProcessedBySession(Long sessionId);
}
