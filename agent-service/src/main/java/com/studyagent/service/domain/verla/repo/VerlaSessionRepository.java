package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaSession;

import java.util.List;

/**
 * Verla Session 仓储接口
 */
public interface VerlaSessionRepository {

    VerlaSession save(VerlaSession session);

    VerlaSession findById(Long id);

    VerlaSession findByIdForUpdate(Long id);

    List<VerlaSession> findByTurn(Long turnId);

    /**
     * 取同一 turn 内已 SUCCEEDED 的兄弟 session（一般是 plan session 给 agent session 复用结果）
     */
    List<VerlaSession> findCompletedSiblings(Long turnId, Long excludeSessionId);

    VerlaSession findByCorrelationId(String correlationId);

    /**
     * V2 商业化：扣费成功后回填 quota_ledger_id / quota_amount。
     * <p>
     * 乐观保护：仅当原 quota_ledger_id 为空时才写入；若并发已有 ledger 则返回 false 并由
     * 上层判断是否重复扣费（建议直接抛 {@link IllegalStateException} 触发事务回滚）。
     *
     * @return true 绑定成功；false 已有 ledger 或 session 不存在
     */
    boolean bindQuotaLedger(Long sessionId, Long ledgerId, Long amount);

    /**
     * 当前占用 assignment run 派发 slot 的 session 数量（见 {@code AssignmentRunDispatchGate}）。
     */
    int countActiveAssignmentRuns();

    /**
     * 当前占用 capability run 派发 slot 的 session 数量（见 {@code CapabilityRunDispatchGate}）。
     */
    int countActiveCapabilityRuns(String action);
}
