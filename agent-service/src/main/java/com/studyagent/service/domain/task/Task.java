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
    
    /**
     * 领域行为：提交任务
     */
    public Task submit() {
        if (this.status != TaskStatus.DRAFT) {
            throw new IllegalStateException("只能提交草稿状态的任务");
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
            .status(TaskStatus.PENDING)
            .startTime(LocalDateTime.now())
            .build();
    }
    
    /**
     * 领域行为：完成任务
     */
    public Task complete() {
        if (this.status != TaskStatus.IN_PROGRESS) {
            throw new IllegalStateException("只能完成执行中的任务");
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
            .status(TaskStatus.COMPLETED)
            .startTime(this.startTime)
            .finishTime(LocalDateTime.now())
            .build();
    }
}

