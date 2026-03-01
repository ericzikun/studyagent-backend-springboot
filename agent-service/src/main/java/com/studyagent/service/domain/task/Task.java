package com.studyagent.service.domain.task;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务领域模型
 */
@Value
@Builder
public class Task {
    TaskId id;
    String clerkUserId;
    String taskTitle;
    String taskDesc;
    Integer subject;
    Integer academicLevel;
    Integer priorityLevel;
    LocalDateTime dueDate;
    List<Integer> format;
    Integer citationStyle;
    Integer pageLength;
    String specialInstructions;
    TaskStatus status;
    LocalDateTime startTime;
    LocalDateTime finishTime;
    Integer costTime;
    java.math.BigDecimal completePercent;
    Integer taskCompletedSize;
    Integer activeAgentSize;
    Integer estRemainingTime;
    String requirementJson;
    String finalResult;
    String errorMessage;
    /** 全链路追踪ID，由请求入口（TraceIdFilter）注入，贯穿任务全生命周期供 Python 执行使用 */
    String traceId;
    
    /**
     * 领域行为：提交任务
     */
    public Task submit() {
        if (this.status != TaskStatus.DRAFT) {
            throw new IllegalStateException("Can only submit tasks with DRAFT status");
        }
        return Task.builder()
            .id(this.id)
            .clerkUserId(this.clerkUserId)
            .taskTitle(this.taskTitle)
            .taskDesc(this.taskDesc)
            .subject(this.subject)
            .academicLevel(this.academicLevel)
            .priorityLevel(this.priorityLevel)
            .dueDate(this.dueDate)
            .format(this.format)
            .citationStyle(this.citationStyle)
            .pageLength(this.pageLength)
            .specialInstructions(this.specialInstructions)
            .requirementJson(this.requirementJson)
            .status(TaskStatus.PENDING)
            .startTime(LocalDateTime.now())
            .traceId(this.traceId)
            .build();
    }
    
    /**
     * 领域行为：完成任务
     */
    public Task complete() {
        if (this.status != TaskStatus.IN_PROGRESS) {
            throw new IllegalStateException("Can only complete tasks in IN_PROGRESS status");
        }
        return Task.builder()
            .id(this.id)
            .clerkUserId(this.clerkUserId)
            .taskTitle(this.taskTitle)
            .taskDesc(this.taskDesc)
            .subject(this.subject)
            .academicLevel(this.academicLevel)
            .priorityLevel(this.priorityLevel)
            .dueDate(this.dueDate)
            .format(this.format)
            .citationStyle(this.citationStyle)
            .pageLength(this.pageLength)
            .specialInstructions(this.specialInstructions)
            .requirementJson(this.requirementJson)
            .status(TaskStatus.COMPLETED)
            .startTime(this.startTime)
            .finishTime(LocalDateTime.now())
            .traceId(this.traceId)
            .build();
    }
}

