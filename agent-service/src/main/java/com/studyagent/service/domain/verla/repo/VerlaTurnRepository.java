package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaTurn;

import java.util.List;
import java.util.Map;

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

    List<VerlaTurn> findByIds(List<Long> turnIds);

    /** 批量拉取多个 conversation 的 turns（按 conversation_id, id DESC 排序，调用方分组截断）。 */
    Map<Long, List<VerlaTurn>> findRecentByConversationIds(List<Long> conversationIds);
}
