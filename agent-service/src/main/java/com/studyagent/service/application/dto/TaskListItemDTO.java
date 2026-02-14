package com.studyagent.service.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务列表项 DTO（应用层）
 * 与 API 层 TaskListItemResponse 结构一致，便于转换
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskListItemDTO {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IdValue {
        private Long value;
    }

    private IdValue id;
    private String clerkUserId;
    private String taskTitle;
    private String taskDesc;
    private Integer subject;
    private Integer academicLevel;
    private Integer priorityLevel;
    private String dueDate;
    private List<Integer> format;
    private Integer citationStyle;
    private Integer pageLength;
    private String specialInstructions;
    private String status;
    private String startTime;
    private String finishTime;
    private Integer costTime;
    private java.math.BigDecimal completePercent;
    private Integer taskCompletedSize;
    private Integer activeAgentSize;
    private Integer estRemainingTime;
    private String requirementJson;
    private String finalResult;
    private String errorMessage;
    private Integer queueAheadCount;
}
