package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.handler.VerlaEventHandlerDispatcher;
import com.studyagent.service.application.verla.sse.VerlaSseEventPayload;
import com.studyagent.service.application.verla.sse.VerlaSsePublisher;
import com.studyagent.service.domain.verla.VerlaEventCursor;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaEventCursorRepository;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Verla 事件入站保序处理服务（PR-12）
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §8.3 / §11.4 / §11.5。
 * <ul>
 *     <li>{@link #ingest(VerlaEventEnvelope)} —— listener 直接调；幂等 + 顺序判定 + 触发 drain</li>
 *     <li>{@link #drainStuckSession(Long)} —— 兜底 drain 入口（gap watcher 用）</li>
 * </ul>
 */
@Slf4j
@Service
public class VerlaInboxService {

    private final VerlaEventInboxRepository inboxRepository;
    private final VerlaEventCursorRepository cursorRepository;
    private final VerlaEventHandlerDispatcher handlerDispatcher;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    /** 可选：在 unit test / mock-only profile 下可能不存在 */
    private final ObjectProvider<VerlaSsePublisher> ssePublisherProvider;

    private Counter dupCounter;
    private Counter skippedCounter;
    private Counter readyHoldCounter;
    private Counter processedCounter;
    private Counter failedCounter;
    private Counter ssePublishCounter;

    public VerlaInboxService(VerlaEventInboxRepository inboxRepository,
                             VerlaEventCursorRepository cursorRepository,
                             VerlaEventHandlerDispatcher handlerDispatcher,
                             MeterRegistry meterRegistry,
                             ObjectMapper objectMapper,
                             ObjectProvider<VerlaSsePublisher> ssePublisherProvider) {
        this.inboxRepository = inboxRepository;
        this.cursorRepository = cursorRepository;
        this.handlerDispatcher = handlerDispatcher;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.ssePublisherProvider = ssePublisherProvider;
    }

    @PostConstruct
    void init() {
        dupCounter = meterRegistry.counter("verla.event.inbox.duplicate.total");
        skippedCounter = meterRegistry.counter("verla.event.inbox.skipped.total");
        readyHoldCounter = meterRegistry.counter("verla.event.inbox.ready_hold.total");
        processedCounter = meterRegistry.counter("verla.event.inbox.processed.total");
        failedCounter = meterRegistry.counter("verla.event.inbox.failed.total");
        ssePublishCounter = meterRegistry.counter("verla.event.inbox.sse_publish.total");
    }

    /**
     * 单事件入站 —— 主入口。
     * <p>
     * 整个方法在单事务中：插 inbox → 锁 cursor → 判定 → 可能 drain。
     * Handler 通过 dispatcher 在 **同事务** 内被调用；handler 抛异常会回滚整批 drain。
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void ingest(VerlaEventEnvelope env) {
        validate(env);

        VerlaEventInbox row = toInboxRow(env);
        boolean inserted = inboxRepository.tryInsert(row);
        if (!inserted) {
            dupCounter.increment();
            log.debug("[Verla/inbox] duplicate event ignored: messageId={} sessionId={} seq={}",
                    env.getMessageId(), env.getSession().getSessionId(), env.getEventSeq());
            return;
        }

        Long sessionId = env.getSession().getSessionId();
        Long convId = env.getConversation().getConversationId();
        Long turnId = env.getTurn().getTurnId();
        long incomingSeq = env.getEventSeq();

        VerlaEventCursor cursor = cursorRepository.lockOrInit(sessionId, convId, turnId);
        long expected = cursor.getNextExpectedSeq();

        if (incomingSeq < expected) {
            inboxRepository.markSkipped(row.getId(),
                    "stale seq=" + incomingSeq + " < expected=" + expected);
            skippedCounter.increment();
            log.warn("[Verla/inbox] stale event marked SKIPPED: sessionId={} seq={} expected={}",
                    sessionId, incomingSeq, expected);
            return;
        }
        if (incomingSeq > expected) {
            // 提前到达：留 inbox 等齐前面的，由后续 ingest 或定时 drain 推进
            readyHoldCounter.increment();
            log.info("[Verla/inbox] early event held in inbox: sessionId={} seq={} expected={}",
                    sessionId, incomingSeq, expected);
            return;
        }

        drainAndDispatch(sessionId, cursor);
    }

    /**
     * 兜底入口：被 GapWatcher / 定时器调，对单 session 尝试推进。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void drainStuckSession(Long sessionId) {
        VerlaEventCursor cursor = cursorRepository.findById(sessionId);
        if (cursor == null) {
            log.debug("[Verla/inbox] drainStuckSession: cursor missing sessionId={}", sessionId);
            return;
        }
        // 重新 SELECT FOR UPDATE 锁住
        VerlaEventCursor locked = cursorRepository.lockOrInit(
                sessionId, cursor.getConversationId(), cursor.getTurnId());
        drainAndDispatch(sessionId, locked);
    }

    /**
     * 兜底批量扫描：每个被发现的 stuck session 单独开事务推进，互不影响。
     */
    public int drainAllStuckSessions(int limit) {
        List<Long> ids = inboxRepository.findStuckSessions(limit);
        if (ids.isEmpty()) {
            return 0;
        }
        int advanced = 0;
        for (Long sid : ids) {
            try {
                drainStuckSession(sid);
                advanced++;
            } catch (Exception e) {
                log.error("[Verla/inbox] drain stuck session={} failed", sid, e);
            }
        }
        return advanced;
    }

    // ===========================================================
    // internal
    // ===========================================================

    private void drainAndDispatch(Long sessionId, VerlaEventCursor cursor) {
        long seq = cursor.getNextExpectedSeq();
        long lastProcessed = cursor.getLastProcessedSeq();
        int batched = 0;
        // 收集本次 drain 中所有"已 markProcessed 成功"的 (row, env) 对，事务 commit 后批量推 SSE
        List<VerlaSseEventPayload> ssePending = new ArrayList<>();
        while (true) {
            VerlaEventInbox ready = inboxRepository.findReady(sessionId, seq);
            if (ready == null) {
                break;
            }
            VerlaEventEnvelope env = parsePayloadEnvelope(ready);
            try {
                handlerDispatcher.dispatch(ready, env);
                inboxRepository.markProcessed(ready.getId());
                processedCounter.increment();
                ssePending.add(toSsePayload(ready, env));
                lastProcessed = seq;
                seq++;
                batched++;
            } catch (RuntimeException e) {
                inboxRepository.markFailed(ready.getId(),
                        truncate(e.getMessage(), 1000));
                failedCounter.increment();
                log.error("[Verla/inbox] handler failed: sessionId={} seq={} type={}",
                        sessionId, seq, ready.getEventType(), e);
                throw e;
            }
        }
        if (batched > 0) {
            cursorRepository.advance(sessionId, seq, lastProcessed);
            log.info("[Verla/inbox] drained sessionId={} count={} nextExpected={}",
                    sessionId, batched, seq);
            scheduleAfterCommitPublish(cursor.getConversationId(), ssePending);
        }
    }

    /**
     * 仅在事务提交后才广播；外层事务回滚时本批 SSE 一并丢弃，避免前端看到"幻觉"事件。
     * <p>
     * 若当前不在事务中（理论上不会发生，因为 ingest/drain 都加了 @Transactional），
     * 兜底立即推送，保证 mock / dev 场景仍能跑通。
     */
    private void scheduleAfterCommitPublish(Long conversationId, List<VerlaSseEventPayload> pending) {
        if (pending == null || pending.isEmpty() || conversationId == null) {
            return;
        }
        VerlaSsePublisher publisher = ssePublisherProvider.getIfAvailable();
        if (publisher == null) {
            log.debug("[Verla/inbox] no VerlaSsePublisher bean, skip SSE push (cid={}, count={})",
                    conversationId, pending.size());
            return;
        }
        Runnable flush = () -> {
            for (VerlaSseEventPayload p : pending) {
                try {
                    publisher.publish(conversationId, p);
                    ssePublishCounter.increment();
                } catch (Exception ex) {
                    // SSE 失败不影响业务事务；前端可通过 Last-Event-ID 重连补发
                    log.warn("[Verla/inbox] SSE publish failed cid={} eventId={} type={}: {}",
                            conversationId, p.getId(), p.getType(), ex.getMessage());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    flush.run();
                }
            });
        } else {
            flush.run();
        }
    }

    private VerlaSseEventPayload toSsePayload(VerlaEventInbox row, VerlaEventEnvelope env) {
        return VerlaSseEventPayload.builder()
                .id(row.getId())
                .type(row.getEventType())
                .conversationId(row.getConversationId())
                .turnId(row.getTurnId())
                .sessionId(row.getSessionId())
                .stepId(row.getStepId())
                .stepSeq(row.getStepSeq())
                .payload(env == null ? null : env.getPayload())
                .build();
    }

    private VerlaEventInbox toInboxRow(VerlaEventEnvelope env) {
        return VerlaEventInbox.builder()
                .messageId(resolveMessageId(env))
                .correlationId(env.getCorrelationId())
                .conversationId(env.getConversation().getConversationId())
                .turnId(env.getTurn().getTurnId())
                .sessionId(env.getSession().getSessionId())
                .eventSeq(env.getEventSeq())
                .eventType(env.getEventType())
                .stepId(env.getStep() == null ? null : env.getStep().getStepId())
                .stepSeq(env.getStep() == null ? null : env.getStep().getStepSeq())
                .payloadJson(serializePayload(env))
                .status(VerlaEventInbox.STATUS_READY)
                .receivedAt(LocalDateTime.now())
                .build();
    }

    private String serializePayload(VerlaEventEnvelope env) {
        try {
            return objectMapper.writeValueAsString(env);
        } catch (Exception e) {
            log.warn("[Verla/inbox] failed to serialize envelope, fallback to payload only: {}",
                    e.getMessage());
            try {
                return env.getPayload() == null
                        ? "{}"
                        : objectMapper.writeValueAsString(env.getPayload());
            } catch (Exception ignored) {
                return "{}";
            }
        }
    }

    /**
     * 反序列化整个 envelope；仅 handler 路径用，失败时返回 null（dispatcher 容忍 null）。
     */
    private VerlaEventEnvelope parsePayloadEnvelope(VerlaEventInbox row) {
        if (row == null || row.getPayloadJson() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(row.getPayloadJson(), VerlaEventEnvelope.class);
        } catch (Exception e) {
            log.warn("[Verla/inbox] payload parse failed, sessionId={} seq={} type={}: {}",
                    row.getSessionId(), row.getEventSeq(), row.getEventType(), e.getMessage());
            return null;
        }
    }

    private static String resolveMessageId(VerlaEventEnvelope env) {
        if (env.getMessageId() != null && !env.getMessageId().isBlank()) {
            return env.getMessageId();
        }
        if (env.getEventId() != null && !env.getEventId().isBlank()) {
            return env.getEventId();
        }
        throw new BusinessException(ApiCode.PARAM_ERROR, "envelope missing messageId/eventId");
    }

    private static void validate(VerlaEventEnvelope env) {
        if (env == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "envelope is null");
        }
        if (env.getSession() == null || env.getSession().getSessionId() == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "envelope.session missing");
        }
        if (env.getConversation() == null || env.getConversation().getConversationId() == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "envelope.conversation missing");
        }
        if (env.getTurn() == null || env.getTurn().getTurnId() == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "envelope.turn missing");
        }
        if (env.getEventSeq() == null || env.getEventSeq() < 1) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "envelope.eventSeq invalid");
        }
        if (env.getEventType() == null || env.getEventType().isBlank()) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "envelope.eventType missing");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
