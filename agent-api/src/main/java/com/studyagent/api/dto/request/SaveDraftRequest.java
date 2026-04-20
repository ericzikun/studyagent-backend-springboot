package com.studyagent.api.dto.request;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 保存草稿请求
 */
@Data
public class SaveDraftRequest {
    /** 草稿 ID（Sqids 编码，可选，有则更新无则创建） */
    private String draftId;
    private String taskDesc;
    private Integer subject;
    private Integer academicLevel;
    private Integer priorityLevel;
    private LocalDateTime dueDate;
    private List<String> objectIds;
    private List<Integer> format;
    private Integer citationStyle;
    private Integer pageLength;
    private String specialInstructions;
    private String clarifyingQuestions;
    private String requirementsJson;
}
