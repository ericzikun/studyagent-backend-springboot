package com.studyagent.service.domain.verla.dispatch;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Assignment Workforce session 进入终态后发布，用于尽快冲刷仍在 outbox 中等待 slot 的命令。
 */
@Getter
public class AssignmentRunSlotReleasedEvent extends ApplicationEvent {

    private final Long sessionId;

    public AssignmentRunSlotReleasedEvent(Object source, Long sessionId) {
        super(source);
        this.sessionId = sessionId;
    }
}
