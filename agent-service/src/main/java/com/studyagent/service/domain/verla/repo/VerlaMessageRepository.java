package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaMessage;

import java.util.List;

/**
 * Verla Message 仓储接口
 */
public interface VerlaMessageRepository {

    VerlaMessage save(VerlaMessage message);

    VerlaMessage findById(Long id);

    /**
     * 主对话历史游标分页（id < cursor 倒序，cursor 为 null 时取最新）。
     * 文件对话消息通过 scene=FILE_CHAT 隔离，不进入主聊天历史。
     */
    List<VerlaMessage> findByCursor(Long conversationId, Long cursor, int limit);

    /**
     * 文件对话历史分页（按 conversation + objectId 隔离）
     */
    List<VerlaMessage> findFileChatByCursor(Long conversationId, String objectId, Long cursor, int limit);

    /**
     * 作业追问（Chat With Assignment）历史分页（scene=ASSIGNMENT_CHAT，键到 conversation 隔离）。
     * <p>
     * default 方法：生产实现 {@code VerlaMessageRepositoryImpl} 覆盖；测试 fake 无需实现。
     */
    default List<VerlaMessage> findAssignmentChatByCursor(Long conversationId, Long cursor, int limit) {
        return List.of();
    }

    /**
     * Find an isolated scene message by turn and role for idempotent terminal writeback.
     */
    default VerlaMessage findByTurnRoleScene(Long turnId, String role, String scene) {
        return null;
    }
}
