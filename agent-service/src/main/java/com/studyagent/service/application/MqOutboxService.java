package com.studyagent.service.application;

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
}
