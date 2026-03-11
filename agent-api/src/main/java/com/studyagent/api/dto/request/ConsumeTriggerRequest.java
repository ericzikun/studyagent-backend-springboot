package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 消费触发请求
 */
@Data
public class ConsumeTriggerRequest {

    @NotBlank(message = "triggerCode cannot be empty")
    private String triggerCode;

    @NotBlank(message = "subjectType cannot be empty")
    private String subjectType;

    @NotNull(message = "subjectId cannot be null")
    private Object subjectId;

    private String sourcePage;
}
