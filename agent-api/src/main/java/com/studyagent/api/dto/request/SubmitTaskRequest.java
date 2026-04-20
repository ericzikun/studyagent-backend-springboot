package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提交任务请求
 */
@Data
public class SubmitTaskRequest {
    /** 草稿ID（Sqids 编码，可选） */
    private String draftId;

    private String taskDesc;
    
    @NotNull(message = "学科不能为空")
    private Integer subject;
    
    @NotNull(message = "学术级别不能为空")
    private Integer academicLevel;
    
    @NotNull(message = "优先级不能为空")
    private Integer priorityLevel;
    
    private LocalDateTime dueDate; // 截止时间，可选
    
    private List<String> objectIds; // 文件object_id列表
    
    @NotNull(message = "输出格式不能为空")
    private List<Integer> format; // 输出格式列表
    
    @NotNull(message = "引用格式不能为空")
    private Integer citationStyle;
    
    private Integer pageLength; // 页数要求，可选
    
    private String specialInstructions;

    /** 追问问题与回答（JSON字符串） */
    private String clarifyingQuestions;

    /** 需求补充（JSON字符串） */
    private String requirementsJson;
}
