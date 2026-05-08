package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.VerlaEventCursorEntity;
import com.studyagent.infra.mapper.verla.VerlaEventCursorMapper;
import com.studyagent.service.domain.verla.VerlaEventCursor;
import com.studyagent.service.domain.verla.repo.VerlaEventCursorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Slf4j
@Repository
public class VerlaEventCursorRepositoryImpl
        extends ServiceImpl<VerlaEventCursorMapper, VerlaEventCursorEntity>
        implements VerlaEventCursorRepository {

    @Override
    public VerlaEventCursor lockOrInit(Long sessionId, Long conversationId, Long turnId) {
        VerlaEventCursorEntity row = this.baseMapper.selectForUpdate(sessionId);
        if (row != null) {
            return toDomain(row);
        }
        // 不存在 → 插一行，再 SELECT FOR UPDATE 拿到行锁
        VerlaEventCursorEntity init = new VerlaEventCursorEntity()
                .setSessionId(sessionId)
                .setConversationId(conversationId)
                .setTurnId(turnId)
                .setNextExpectedSeq(1L)
                .setLastProcessedSeq(0L)
                .setUpdatedAt(LocalDateTime.now());
        try {
            this.save(init);
        } catch (DuplicateKeyException dup) {
            // 并发首次插入：忽略，重新 SELECT FOR UPDATE
            log.debug("[Verla/cursor] init race for session={}, falling back to lock", sessionId);
        }
        VerlaEventCursorEntity locked = this.baseMapper.selectForUpdate(sessionId);
        return toDomain(locked);
    }

    @Override
    public VerlaEventCursor findById(Long sessionId) {
        return toDomain(this.getById(sessionId));
    }

    @Override
    public int advance(Long sessionId, Long newNextExpected, Long newLastProcessed) {
        return this.baseMapper.advance(sessionId, newNextExpected, newLastProcessed, LocalDateTime.now());
    }

    private VerlaEventCursor toDomain(VerlaEventCursorEntity e) {
        if (e == null) {
            return null;
        }
        return VerlaEventCursor.builder()
                .sessionId(e.getSessionId())
                .conversationId(e.getConversationId())
                .turnId(e.getTurnId())
                .nextExpectedSeq(e.getNextExpectedSeq())
                .lastProcessedSeq(e.getLastProcessedSeq())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
