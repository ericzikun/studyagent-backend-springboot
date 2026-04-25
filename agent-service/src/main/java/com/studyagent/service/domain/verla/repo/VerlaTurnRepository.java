package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaTurn;

import java.util.List;

/**
 * Verla Turn 仓储接口
 */
public interface VerlaTurnRepository {

    VerlaTurn save(VerlaTurn turn);

    VerlaTurn findById(Long id);

    /**
     * 行锁加载（用于状态机转换） SELECT ... FOR UPDATE
     */
    VerlaTurn findByIdForUpdate(Long id);

    List<VerlaTurn> findRecentByConversation(Long conversationId, int limit);
}
