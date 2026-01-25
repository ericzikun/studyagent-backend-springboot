package com.studyagent.service.application.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提交任务请求（应用层）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitTaskRequest {
    private String taskTitle;
    private String taskDesc;
    private Integer subject;
    private Integer academicLevel;
    private Integer priorityLevel;
    private LocalDateTime dueDate;
    private List<Integer> format;
    private Integer citationStyle;
    private Integer pageLength;
    private String specialInstructions;
    private List<String> objectIds;
    private String token; // Clerk token
}

