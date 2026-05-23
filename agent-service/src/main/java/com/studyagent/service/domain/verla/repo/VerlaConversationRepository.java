package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaConversation;

import java.util.List;

/**
 * Verla Conversation 仓储接口
 */
public interface VerlaConversationRepository {

    VerlaConversation save(VerlaConversation conversation);

    VerlaConversation findById(Long id);

    /**
     * @param segmentQueryKey {@link com.studyagent.common.verla.enums.VerlaConversationListSegment#getQueryKey()}，可为 null
     * @param conversationStatusDb {@link com.studyagent.service.domain.verla.state.ConversationStatus#getDbValue()}，可为 null
     */
    List<VerlaConversation> findByUserFilteredPaged(String userId,
                                                   String segmentQueryKey,
                                                   String conversationStatusDb,
                                                   int page,
                                                   int size);

    long countByUserFiltered(String userId,
                             String segmentQueryKey,
                             String conversationStatusDb);

    /**
     * 写新 turn 后调用：自增 version + last_message_at + last_turn_id + turn_count + 1
     */
    int touchOnNewTurn(Long id, Long turnId);

    /**
     * 仅自增 version（写 message / artifact 后让缓存版本前进）
     */
    int incrementVersion(Long id);

    /**
     * 更新 AI 生成的对话标题。
     */
    int updateTitle(Long id, String title);

    default Long touchOnNewTurnAndGetVersion(Long id, Long turnId) {
        touchOnNewTurn(id, turnId);
        VerlaConversation conversation = findById(id);
        return conversation == null ? null : conversation.getVersion();
    }

    default Long incrementVersionAndGet(Long id) {
        incrementVersion(id);
        VerlaConversation conversation = findById(id);
        return conversation == null ? null : conversation.getVersion();
    }
}
