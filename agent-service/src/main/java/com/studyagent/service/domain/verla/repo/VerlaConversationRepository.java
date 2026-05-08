package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaConversation;

import java.util.List;

/**
 * Verla Conversation 仓储接口
 */
public interface VerlaConversationRepository {

    VerlaConversation save(VerlaConversation conversation);

    VerlaConversation findById(Long id);

    List<VerlaConversation> findByUserPaged(String userId, int page, int size);

    /**
     * 写新 turn 后调用：自增 version + last_message_at + last_turn_id + turn_count + 1
     */
    int touchOnNewTurn(Long id, Long turnId);

    /**
     * 仅自增 version（写 message / artifact 后让缓存版本前进）
     */
    int incrementVersion(Long id);
}
