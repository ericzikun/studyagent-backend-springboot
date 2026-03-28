package com.studyagent.service.domain.mq;

import org.springframework.context.ApplicationEvent;

/**
 * MQ 事务发件箱记录创建事件
 */
public class MqOutboxCreatedEvent extends ApplicationEvent {

    private final Long messageId;

    public MqOutboxCreatedEvent(Object source, Long messageId) {
        super(source);
        this.messageId = messageId;
    }

    public Long getMessageId() {
        return messageId;
    }
}
