package com.studyagent.infra.mq;

import com.studyagent.service.domain.mq.MqOutboxCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 事务发件箱即时投递器
 * 监听 MqOutboxCreatedEvent，在所在 Spring 事务提交后，立即触发 MQ 消息投递。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxImmediateDispatcher {

    private final OutboxDispatchScheduler outboxDispatchScheduler;

    /**
     * 事务提交后执行投递（如果没有事务，则退化为立即执行）
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMqOutboxCreated(MqOutboxCreatedEvent event) {
        Long messageId = event.getMessageId();
        log.debug("收到发件箱创建事件，准备即时投递: messageId={}", messageId);
        try {
            outboxDispatchScheduler.dispatchMessageById(messageId);
        } catch (Exception e) {
            log.error("即时投递消息失败，将由定时任务兜底: messageId={}", messageId, e);
        }
    }
}
