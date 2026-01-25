package com.studyagent.api.dto.request;

import lombok.Data;

import java.util.List;

/**
 * 追问任务请求
 */
@Data
public class ClarifyTaskRequest {
    private String taskTitle;
    private String taskDesc;
    private Integer subject;
    private Integer academicLevel;
    private Integer priorityLevel;
    private String dueDate; // ISO 8601 格式字符串
    private List<String> objectIds; // 文件object_id列表
    private List<Integer> format; // 输出格式列表
    private Integer citationStyle;
    private Integer pageLength;
    private String specialInstructions;
}

