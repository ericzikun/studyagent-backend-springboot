package com.studyagent.service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.mq.MqOutboxCreatedEvent;
import com.studyagent.service.domain.mq.MqOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MQ 事务发件箱服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MqOutboxService {

    private final MqOutboxRepository mqOutboxRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 在当前事务内创建并保存发件箱记录。
     * 适用于与业务逻辑（如保存 Task）在同一个事务中的场景。
     *
     * @param action  指令类型
     * @param taskId  任务ID
     * @param payload 业务载荷(JSON)
     * @return 刚保存的 MqOutbox 实体
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public MqOutbox createMessage(String action, Long taskId, String payload) {
        MqOutbox message = MqOutbox.builder()
                .eventId(UUID.randomUUID().toString())
                .action(action)
                .taskId(taskId)
                .payload(payload)
                .status(MqOutbox.STATUS_UNSENT)
                .retryCount(0)
                .maxRetries(5)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        MqOutbox saved = mqOutboxRepository.save(message);
        log.info("本地消息已写入: action={}, taskId={}, eventId={}", action, taskId, message.getEventId());

        // 发布事件，通知订阅者（如 OutboxImmediateDispatcher）可以开始投递了
        eventPublisher.publishEvent(new MqOutboxCreatedEvent(this, saved.getId()));

        return saved;
    }

    /**
     * 在新的独立事务中创建并保存发件箱记录。
     * 适用于当前没有事务，或者希望脱离当前长事务独立提交的场景。
     *
     * @param action  指令类型
     * @param taskId  任务ID
     * @param payload 业务载荷(JSON)
     * @return 刚保存的 MqOutbox 实体
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MqOutbox createMessageInNewTransaction(String action, Long taskId, String payload) {
        return createMessage(action, taskId, payload);
    }

    // ============================================================
    //  Verla 链路：写入信封 + 指定 exchange / routingKey
    //  详见 docs/verla-Java侧MVP技术方案.md §5 / §6 / §7。
    // ============================================================

    /**
     * 在当前事务内创建一条 Verla 命令到 outbox。
     * <p>
     * 必须在调用方事务内调用，确保 verla_sessions / verla_turns 与 outbox 同事务提交，
     * 提交后由 OutboxImmediateDispatcher 立即发送，定时器兜底重试。
     *
     * @param envelope    完整的 Verla 命令信封（payload 字段会被序列化为 JSON 入库）
     * @param exchange    目标 exchange（一般是 RabbitMQConfig.COMMAND_EXCHANGE）
     * @param routingKey  路由键（如 verla.cmd.plan / verla.cmd.agent）
     * @return 保存后的 outbox 行
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public MqOutbox createVerlaCommand(VerlaCommandEnvelope envelope,
                                       String exchange,
                                       String routingKey) {
        if (envelope == null || envelope.getSession() == null) {
            throw new IllegalArgumentException("Verla envelope or session ref is null");
        }
        String payload = serialize(envelope);
        Long sessionId = envelope.getSession().getSessionId();
        Long turnId = envelope.getTurn() == null ? null : envelope.getTurn().getTurnId();
        Long convId = envelope.getConversation() == null ? null
                : envelope.getConversation().getConversationId();

        MqOutbox message = MqOutbox.builder()
                .eventId(envelope.getMessageId())
                .action(envelope.getAction())
                .taskId(null)
                .payload(payload)
                .status(MqOutbox.STATUS_UNSENT)
                .retryCount(0)
                .maxRetries(5)
                .correlationId(envelope.getCorrelationId())
                .orderingKey("session:" + sessionId)
                .schemaVersion(envelope.getSchemaVersion() == null ? 1 : envelope.getSchemaVersion())
                .conversationId(convId)
                .turnId(turnId)
                .sessionId(sessionId)
                .exchange(exchange)
                .routingKey(routingKey)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        MqOutbox saved = mqOutboxRepository.save(message);
        log.info("Verla 命令已写入 outbox: action={}, routingKey={}, sessionId={}, messageId={}",
                envelope.getAction(), routingKey, sessionId, envelope.getMessageId());

        eventPublisher.publishEvent(new MqOutboxCreatedEvent(this, saved.getId()));
        return saved;
    }

    private String serialize(VerlaCommandEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize VerlaCommandEnvelope", e);
        }
    }
}
