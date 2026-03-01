package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 任务表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tasks")
public class TaskEntity extends BaseEntity {
    private String clerkUserId;
    private String taskTitle;
    private String taskDesc;
    private Integer subject;
    private Integer academicLevel;
    private Integer priorityLevel;
    private LocalDateTime dueDate;
    private String format;
    private Integer citationStyle;
    private Integer pageLength;
    private String specialInstructions;
    private Integer status;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private Integer costTime;
    private java.math.BigDecimal completePercent;
    private Integer taskCompletedSize;
    private Integer activeAgentSize;
    /**
     * COMPOSE 阶段总轮数（计划章节数），进入 COMPOSE 后由 Python 写入。
     * 用于 SECTIONS DRAFTED 展示，优先于 activeAgentSize。
     */
    @TableField("compose_total_rounds")
    private Integer composeTotalRounds;
    private Integer estRemainingTime;
    private String requirementJson;
    private String finalResult;
    private String errorMessage;
    /** 全链路追踪ID，与 HTTP 请求 TraceId 一致，供 Python 执行贯穿使用 */
    private String traceId;
    /** 逻辑删除: 0-未删除, 1-已删除 */
    @TableField("is_deleted")
    private Integer isDeleted;
}

