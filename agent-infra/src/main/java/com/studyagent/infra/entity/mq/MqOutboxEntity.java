package com.studyagent.infra.entity.mq;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * MQ 事务发件箱实体类
 * 对应表: mq_outbox
 */
@Data
@Accessors(chain = true)
@TableName("mq_outbox")
public class MqOutboxEntity {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 全局唯一事件ID，用于消费端去重，例如: UUID
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
     * 状态：0=UNSENT, 1=SENT, 2=FAILED
     */
    private Integer status;

    /**
     * 已重试次数
     */
    private Integer retryCount;

    /**
     * 最大重试次数
     */
    private Integer maxRetries;

    /**
     * 下次重试时间
     */
    private LocalDateTime nextRetryAt;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
