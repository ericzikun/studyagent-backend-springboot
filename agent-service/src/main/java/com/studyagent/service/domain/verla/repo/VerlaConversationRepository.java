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
     * 关键词模糊搜索（标题 + 消息正文），{@code keywordPattern} 为已转义 LIKE 模式片段（不含首尾 %）。
     */
    default List<VerlaConversation> searchByUserKeywordPaged(String userId,
                                                             String keywordPattern,
                                                             String segmentQueryKey,
                                                             String conversationStatusDb,
                                                             int page,
                                                             int size) {
        return List.of();
    }

    default long countByUserKeyword(String userId,
                                    String keywordPattern,
                                    String segmentQueryKey,
                                    String conversationStatusDb) {
        return 0L;
    }

    default List<VerlaConversation> findAdminFilteredPaged(String ownerUserId,
                                                           String segmentQueryKey,
                                                           String conversationStatusDb,
                                                           boolean excludeInternal,
                                                           int page,
                                                           int size) {
        return List.of();
    }

    default long countAdminFiltered(String ownerUserId,
                                    String segmentQueryKey,
                                    String conversationStatusDb,
                                    boolean excludeInternal) {
        return 0L;
    }

    default List<VerlaConversation> searchAdminKeywordPaged(String ownerUserId,
                                                            String keywordPattern,
                                                            String segmentQueryKey,
                                                            String conversationStatusDb,
                                                            boolean excludeInternal,
                                                            int page,
                                                            int size) {
        return List.of();
    }

    default long countAdminKeyword(String ownerUserId,
                                   String keywordPattern,
                                   String segmentQueryKey,
                                   String conversationStatusDb,
                                   boolean excludeInternal) {
        return 0L;
    }

    /**
     * 写新 turn 后调用：自增 version + last_message_at + last_turn_id + turn_count + 1
     */
    int touchOnNewTurn(Long id, Long turnId);

    /**
     * 仅自增 version（写 message / artifact 后让缓存版本前进）
     */
    int incrementVersion(Long id);

    /**
     * 刷新改动时间 last_active_at = NOW()（用户点击任务 / 编辑内容更新时调用）。
     * 不改变 version / turn_count，避免触发缓存失效与重复计数。
     * <p>默认空实现仅服务于测试桩；生产实现（DB / cache-aware）均需覆盖。
     */
    default int touchActiveAt(Long id) {
        return 0;
    }

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
