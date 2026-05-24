package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaWorkforceTaskOutput;

import java.util.List;
import java.util.Optional;

/**
 * Verla Workforce 任务产出内容仓储接口。
 * <p>
 * 写路径：{@link #upsertBySessionNode} 由 {@code ASSIGNMENT_AGENT_NODE_DETAILED} 驱动，
 * 按 (session_id, node_id) 幂等。result_text 追加，detail_items_json JSON 数组合并。
 */
public interface VerlaWorkforceTaskOutputRepository {

    Optional<VerlaWorkforceTaskOutput> findBySessionAndNode(Long sessionId, String nodeId);

    List<VerlaWorkforceTaskOutput> listBySession(Long sessionId);

    /**
     * 按 (session_id, node_id) 幂等 upsert。
     * <ul>
     *   <li>不存在 → insert。</li>
     *   <li>存在 → resultText 追加（非空才追加），detailItemsJson JSON 数组合并（非空才追加）。</li>
     * </ul>
     */
    VerlaWorkforceTaskOutput upsertBySessionNode(VerlaWorkforceTaskOutput patch);
}
