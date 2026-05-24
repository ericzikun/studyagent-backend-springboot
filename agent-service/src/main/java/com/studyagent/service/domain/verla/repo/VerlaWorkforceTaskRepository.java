package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.WorkforceTaskProgressSnapshot;
import com.studyagent.service.domain.verla.VerlaWorkforceTask;

import java.util.List;
import java.util.Optional;

/**
 * Verla Workforce 任务节点快照仓储接口。
 * <p>
 * 写路径：{@link #upsertBySessionNode} 由 {@code ASSIGNMENT_AGENT_NODE_UPDATED} 驱动，
 * 按 (session_id, node_id) 幂等。读路径：listBySession 服务于 canvas 状态恢复。
 */
public interface VerlaWorkforceTaskRepository {

    Optional<VerlaWorkforceTask> findBySessionAndNode(Long sessionId, String nodeId);

    List<VerlaWorkforceTask> listBySession(Long sessionId);

    List<VerlaWorkforceTask> listByConversation(Long conversationId);

    /**
     * 按 (session_id, node_id) 幂等 upsert。
     * <ul>
     *   <li>不存在 → insert，createdAt/updatedAt = now。</li>
     *   <li>存在 → 非空字段才覆盖，updatedAt 刷新。</li>
     *   <li>status 一旦进入终态（completed / failed）不回退到 queued / running。</li>
     * </ul>
     */
    VerlaWorkforceTask upsertBySessionNode(VerlaWorkforceTask patch);

    /**
     * Session 维度进度聚合，替代 V1 {@code tasks.task_completed_size} /
     * {@code active_agent_size} / {@code compose_total_rounds} 读法。
     */
    WorkforceTaskProgressSnapshot aggregateProgressBySession(Long sessionId);
}
