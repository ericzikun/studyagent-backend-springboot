package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaMessage;

import java.util.List;
import java.util.Map;

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
     * 白板教学（Video Tutor）历史分页（scene=VIDEO_TUTOR）。
     * <p>
     * 用途有二：P2-3 每轮把历史随命令下发给 Python（Python 不持有会话状态），
     * 以及 P2-5 snapshot 返回左栏消息。default 空实现供测试桩，生产实现覆盖。
     */
    default List<VerlaMessage> findVideoTutorByCursor(Long conversationId, Long cursor, int limit) {
        return List.of();
    }

    /**
     * Find an isolated scene message by turn and role for idempotent terminal writeback.
     */
    default VerlaMessage findByTurnRoleScene(Long turnId, String role, String scene) {
        return null;
    }

    /**
     * 同一 turn 同一 formId 的作业澄清提交；仅供 finalize 幂等去重，生产实现必须加锁读。
     */
    default VerlaMessage findUserClarifyByTurnAndFormIdForUpdate(Long turnId, String formId) {
        return null;
    }

    /**
     * 批量取每个 conversation 第一条 user 主对话消息的 query 拼接串（text_content + blocks_json）。
     * admin 列表用；default 空实现供测试桩，生产实现覆盖。
     */
    default Map<Long, String> findFirstUserQueryByConversationIds(List<Long> conversationIds) {
        return Map.of();
    }
}
