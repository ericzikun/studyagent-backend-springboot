package com.studyagent.infra.entity;

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
    private Integer estRemainingTime;
    private String requirementJson;
    private String finalResult;
    private String errorMessage;
}

