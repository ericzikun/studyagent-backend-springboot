package com.studyagent.service.application.verla.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.dispatch.AssignmentRunDispatchActions;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaConversationRef;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.VerlaSessionRef;
import com.studyagent.common.verla.envelope.VerlaTurnRef;
import com.studyagent.service.application.verla.sse.VerlaSseEventPayload;
import com.studyagent.service.application.verla.sse.VerlaSsePublisher;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.mq.MqOutboxRepository;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.dispatch.AssignmentRunDispatchGate;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 当 assignment run 因 Java 派发门控暂缓 / 放出时，写入 inbox 并 SSE 通知前端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssignmentRunDispatchQueueNotifier implements AssignmentRunDispatchQueueEvents {

    private final MqOutboxRepository mqOutboxRepository;
    private final AssignmentRunDispatchGate assignmentRunDispatchGate;
    private final VerlaEventInboxRepository inboxRepository;
    private final ObjectProvider<VerlaSsePublisher> ssePublisherProvider;
    private final ObjectMapper objectMapper;

    /**
     * 在 outbox 被门控 defer 后调用；幂等键含 queuePosition，位置变化时会再次通知。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void notifyDeferred(MqOutbox message) {
        if (!canNotify(message)) {
            return;
        }

        LocalDateTime createdAt = message.getCreatedAt() == null
                ? LocalDateTime.now() : message.getCreatedAt();
        int queuePosition = mqOutboxRepository.countDeferredAssignmentRunAhead(
                message.getId(), createdAt);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("queuePosition", queuePosition);
        payload.put("maxConcurrency", assignmentRunDispatchGate.maxConcurrency());
        payload.put("activeCount", assignmentRunDispatchGate.activeCount());
        payload.put("reason", "dispatch_gate");

        String messageId = "java:assignment-run-dispatch-queued:"
                + message.getId() + ":pos:" + queuePosition;
        publishIfAbsent(
                message,
                messageId,
                VerlaAgentEventType.ASSIGNMENT_RUN_DISPATCH_QUEUED,
                payload,
                "[Verla/assignment-run-dispatch] queued event published inboxId={} sessionId={} queuePosition={} active={} max={}",
                queuePosition,
                assignmentRunDispatchGate.activeCount(),
                assignmentRunDispatchGate.maxConcurrency());
    }

    /**
     * 在 outbox Broker ack / markAsSent 后调用；幂等键按 outboxId，避免重复清排队。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void notifyDispatched(MqOutbox message) {
        if (!canNotify(message)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("queuePosition", null);
        payload.put("maxConcurrency", assignmentRunDispatchGate.maxConcurrency());
        payload.put("activeCount", assignmentRunDispatchGate.activeCount());
        payload.put("reason", "dispatch_gate_released");
        payload.put("label", "Starting assignment workflow");

        String messageId = "java:assignment-run-dispatched:" + message.getId();
        publishIfAbsent(
                message,
                messageId,
                VerlaAgentEventType.ASSIGNMENT_RUN_DISPATCHED,
                payload,
                "[Verla/assignment-run-dispatch] dispatched event published inboxId={} sessionId={} active={} max={}",
                assignmentRunDispatchGate.activeCount(),
                assignmentRunDispatchGate.maxConcurrency());
    }

    private boolean canNotify(MqOutbox message) {
        if (message == null || !AssignmentRunDispatchActions.isGated(message.getAction())) {
            return false;
        }
        if (message.getConversationId() == null || message.getSessionId() == null) {
            log.debug("[Verla/assignment-run-dispatch] skip queue notify: missing conv/session outboxId={}",
                    message.getId());
            return false;
        }
        return true;
    }

    private void publishIfAbsent(
            MqOutbox message,
            String messageId,
            VerlaAgentEventType eventType,
            Map<String, Object> payload,
            String successLog,
            Object... successLogArgs) {
        if (inboxRepository.findByMessageId(messageId) != null) {
            return;
        }

        VerlaEventEnvelope envelope = VerlaEventEnvelope.builder()
                .messageId(messageId)
                .correlationId(message.getCorrelationId())
                .schemaVersion(message.getSchemaVersion() == null ? 1 : message.getSchemaVersion())
                .eventType(eventType.name())
                .eventSeq(0L)
                .conversation(VerlaConversationRef.builder()
                        .conversationId(message.getConversationId())
                        .build())
                .turn(message.getTurnId() == null ? null
                        : VerlaTurnRef.builder()
                        .turnId(message.getTurnId())
                        .build())
                .session(VerlaSessionRef.builder()
                        .sessionId(message.getSessionId())
                        .build())
                .payload(payload)
                .build();

        VerlaEventInbox row = VerlaEventInbox.builder()
                .messageId(messageId)
                .correlationId(message.getCorrelationId())
                .conversationId(message.getConversationId())
                .turnId(message.getTurnId())
                .sessionId(message.getSessionId())
                .eventSeq(0L)
                .eventType(eventType.name())
                .payloadJson(serializeEnvelope(envelope))
                .status(VerlaEventInbox.STATUS_PROCESSED)
                .receivedAt(LocalDateTime.now())
                .processedAt(LocalDateTime.now())
                .build();

        if (!inboxRepository.tryInsert(row)) {
            return;
        }

        VerlaSseEventPayload ssePayload = VerlaSseEventPayload.builder()
                .id(row.getId())
                .type(row.getEventType())
                .conversationId(row.getConversationId())
                .turnId(row.getTurnId())
                .sessionId(row.getSessionId())
                .payload(payload)
                .build();

        Object[] logArgs = new Object[successLogArgs.length + 2];
        logArgs[0] = row.getId();
        logArgs[1] = message.getSessionId();
        System.arraycopy(successLogArgs, 0, logArgs, 2, successLogArgs.length);
        log.info(successLog, logArgs);

        scheduleAfterCommitPublish(message.getConversationId(), ssePayload);
    }

    private void scheduleAfterCommitPublish(Long conversationId, VerlaSseEventPayload payload) {
        if (conversationId == null || payload == null) {
            return;
        }
        Runnable publish = () -> {
            VerlaSsePublisher publisher = ssePublisherProvider.getIfAvailable();
            if (publisher == null) {
                log.debug("[Verla/assignment-run-dispatch] no SSE publisher, skip live push cid={}",
                        conversationId);
                return;
            }
            publisher.publish(conversationId, payload);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    private String serializeEnvelope(VerlaEventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("[Verla/assignment-run-dispatch] envelope serialize failed: {}", e.getMessage());
            return "{\"messageId\":\"" + UUID.randomUUID() + "\"}";
        }
    }
}
