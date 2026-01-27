package com.studyagent.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务列表项响应 DTO（驼峰命名，与前端保持一致）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskListItemResponse {
    
    /**
     * ID 值对象，序列化为 { value: ... }
     */
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
    
    private String dueDate; // ISO 8601 格式
    
    private List<Integer> format;
    
    private Integer citationStyle;
    
    private Integer pageLength;
    
    private String specialInstructions;
    
    private String status; // "PENDING", "COMPLETED", "DRAFT", "FAILED", "IN_PROGRESS"
    
    private String startTime; // ISO 8601 格式，可能为 null
    
    private String finishTime; // ISO 8601 格式，可能为 null
    
    private Integer costTime; // 秒，可能为 null
    
    private java.math.BigDecimal completePercent;
    
    private Integer taskCompletedSize;
    
    private Integer activeAgentSize;
    
    private Integer estRemainingTime; // 可能为 null
    
    private String requirementJson; // 可能为 null
    
    private String finalResult; // 可能为 null
    
    private String errorMessage; // 可能为 null

    /**
     * 任务前方排队数量（为0表示已开始执行或无需排队）
     */
    private Integer queueAheadCount;
}

