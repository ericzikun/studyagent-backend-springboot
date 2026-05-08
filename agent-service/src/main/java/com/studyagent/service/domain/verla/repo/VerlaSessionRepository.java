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
}
