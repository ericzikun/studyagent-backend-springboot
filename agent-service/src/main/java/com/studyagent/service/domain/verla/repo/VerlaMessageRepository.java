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
     * 游标分页（id < cursor 倒序，cursor 为 null 时取最新）
     */
    List<VerlaMessage> findByCursor(Long conversationId, Long cursor, int limit);
}
