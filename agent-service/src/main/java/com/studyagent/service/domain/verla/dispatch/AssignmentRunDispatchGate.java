package com.studyagent.service.domain.verla.dispatch;

/**
 * 作业主执行（Workforce）全局派发并发门控。
 * <p>
 * 在 {@code mq_outbox} 真正发往 RabbitMQ 之前检查；超限时命令留在 outbox 等待 slot 释放。
 */
public interface AssignmentRunDispatchGate {

    boolean isEnabled();

    int maxConcurrency();

    int activeCount();

    /**
     * 当前是否还能向 MQ 再派发一条 assignment run 命令。
     */
    boolean canDispatchNow();
}
