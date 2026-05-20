package com.studyagent.service.domain.mq;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * MQ 事务发件箱领域模型
 */
@Getter
@Builder
public class MqOutbox {

    public static final int STATUS_UNSENT = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_SENDING = 3;

    private Long id;

    /**
     * 全局唯一事件ID，用于去重
     */
    private String eventId;

    /**
     * 指令类型：EXECUTE_TASK, STOP_TASK
     */
    private String action;

    /**
     * 关联的业务任务ID
     */
    private Long taskId;

    /**
     * JSON格式的业务参数
     */
    private String payload;

    /**
     * 状态：0=UNSENT, 1=SENT, 2=FAILED, 3=SENDING
     */
    private Integer status;

    private Integer retryCount;

    private Integer maxRetries;

    private LocalDateTime nextRetryAt;

    private String errorMessage;

    /**
     * 当前 claim / sending worker，用于多实例下避免重复发送和状态覆盖。
     */
    private String workerId;

    /**
     * 当前 claim lease 截止时间。超过该时间的 SENDING 可被重新 claim。
     */
    private LocalDateTime leaseUntil;

    /**
     * 最近一次 claim 时间。
     */
    private LocalDateTime lastClaimedAt;

    // ========== Verla 扩展字段（迁移 027_V2） ==========

    /**
     * Verla 关联 ID：conv:{cid}:turn:{tid}:sess:{sid}
     */
    private String correlationId;

    /**
     * 保序键：session:{sessionId}
     */
    private String orderingKey;

    /**
     * 信封 schema 版本，默认 1
     */
    private Integer schemaVersion;

    /**
     * Verla conversation id（老链路 NULL）
     */
    private Long conversationId;

    /**
     * Verla turn id（老链路 NULL）
     */
    private Long turnId;

    /**
     * Verla session id（老链路 NULL）
     */
    private Long sessionId;

    /**
     * 目标 exchange（NULL 走默认 commandExchange）
     */
    private String exchange;

    /**
     * 路由键（独立于 action）
     */
    private String routingKey;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * 是否可以重试
     */
    public boolean canRetry() {
        return this.retryCount != null && this.maxRetries != null && this.retryCount < this.maxRetries;
    }

    /**
     * 是否是 Verla 链路命令（通过 sessionId 是否为空判断）
     */
    public boolean isVerla() {
        return this.sessionId != null;
    }
}
