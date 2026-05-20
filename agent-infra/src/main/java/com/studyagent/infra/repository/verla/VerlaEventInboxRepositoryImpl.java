package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.VerlaEventInboxEntity;
import com.studyagent.infra.mapper.verla.VerlaEventInboxMapper;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Repository
public class VerlaEventInboxRepositoryImpl
        extends ServiceImpl<VerlaEventInboxMapper, VerlaEventInboxEntity>
        implements VerlaEventInboxRepository {

    @Override
    public boolean tryInsert(VerlaEventInbox row) {
        VerlaEventInboxEntity e = toEntity(row);
        try {
            this.save(e);
            row.setId(e.getId());
            return true;
        } catch (DuplicateKeyException dup) {
            log.debug("[Verla/inbox] duplicate messageId={} ignored", row.getMessageId());
            return false;
        }
    }

    @Override
    public VerlaEventInbox findByMessageId(String messageId) {
        return toDomain(this.baseMapper.selectByMessageId(messageId));
    }

    @Override
    public VerlaEventInbox findReady(Long sessionId, Long expectedSeq) {
        return toDomain(this.baseMapper.selectReady(sessionId, expectedSeq));
    }

    @Override
    public int markProcessed(Long id) {
        return this.baseMapper.markProcessed(id, LocalDateTime.now());
    }

    @Override
    public int markSkipped(Long id, String reason) {
        return this.baseMapper.markSkipped(id, reason, LocalDateTime.now());
    }

    @Override
    public int markFailed(Long id, String reason) {
        return this.baseMapper.markFailed(id, reason, LocalDateTime.now());
    }

    @Override
    public List<Long> findStuckSessions(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return this.baseMapper.selectStuckSessions(safe);
    }

    @Override
    public List<VerlaEventInbox> findReplayByConversation(Long conversationId, Long afterId, int limit) {
        long after = afterId == null ? 0L : afterId;
        int safe = Math.max(1, Math.min(limit, 500));
        return this.baseMapper.selectReplay(conversationId, after, safe)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<VerlaEventInbox> findRecentProcessedByConversation(Long conversationId, int limit) {
        int safe = Math.max(1, Math.min(limit, 100));
        return this.baseMapper.selectRecentProcessed(conversationId, safe)
                .stream().map(this::toDomain).toList();
    }

    private VerlaEventInbox toDomain(VerlaEventInboxEntity e) {
        if (e == null) {
            return null;
        }
        return VerlaEventInbox.builder()
                .id(e.getId())
                .messageId(e.getMessageId())
                .correlationId(e.getCorrelationId())
                .conversationId(e.getConversationId())
                .turnId(e.getTurnId())
                .sessionId(e.getSessionId())
                .eventSeq(e.getEventSeq())
                .eventType(e.getEventType())
                .stepId(e.getStepId())
                .stepSeq(e.getStepSeq())
                .payloadJson(e.getPayloadJson())
                .status(e.getStatus())
                .errorMessage(e.getErrorMessage())
                .receivedAt(e.getReceivedAt())
                .processedAt(e.getProcessedAt())
                .build();
    }

    private VerlaEventInboxEntity toEntity(VerlaEventInbox d) {
        if (d == null) {
            return null;
        }
        return new VerlaEventInboxEntity()
                .setId(d.getId())
                .setMessageId(d.getMessageId())
                .setCorrelationId(d.getCorrelationId())
                .setConversationId(d.getConversationId())
                .setTurnId(d.getTurnId())
                .setSessionId(d.getSessionId())
                .setEventSeq(d.getEventSeq())
                .setEventType(d.getEventType())
                .setStepId(d.getStepId())
                .setStepSeq(d.getStepSeq())
                .setPayloadJson(d.getPayloadJson())
                .setStatus(d.getStatus() == null ? VerlaEventInbox.STATUS_READY : d.getStatus())
                .setErrorMessage(d.getErrorMessage())
                .setReceivedAt(d.getReceivedAt() == null ? LocalDateTime.now() : d.getReceivedAt())
                .setProcessedAt(d.getProcessedAt());
    }
}
