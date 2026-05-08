package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaEventCursor;

/**
 * Verla 事件 cursor 仓储接口
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §8.3。
 */
public interface VerlaEventCursorRepository {

    /**
     * 行锁加载；不存在时插入初始 cursor (next_expected_seq=1, last_processed_seq=0) 后再返回。
     */
    VerlaEventCursor lockOrInit(Long sessionId, Long conversationId, Long turnId);

    VerlaEventCursor findById(Long sessionId);

    /**
     * 推进光标：next_expected_seq = newNextExpected, last_processed_seq = newLastProcessed
     */
    int advance(Long sessionId, Long newNextExpected, Long newLastProcessed);
}
