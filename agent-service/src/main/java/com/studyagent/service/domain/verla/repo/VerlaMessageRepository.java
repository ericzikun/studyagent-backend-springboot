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
}
