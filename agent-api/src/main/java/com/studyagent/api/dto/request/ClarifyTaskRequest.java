package com.studyagent.api.dto.request;

import jakarta.validation.constraints.Pattern;
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

    /**
     * 输出生成所用语言（标准 locale code，可选）
     */
    @Pattern(regexp = "^(en|zh-CN|es|pt|vi|ko|id|tr|fr|ja|hi|de|ru|fil|ms)$",
            message = "outputLanguage must be a supported locale code")
    private String outputLanguage;
}

