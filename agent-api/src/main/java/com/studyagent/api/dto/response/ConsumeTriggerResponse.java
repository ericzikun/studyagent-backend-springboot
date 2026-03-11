package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消费触发响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumeTriggerResponse {

    private boolean shouldPrompt;
    private String promptSessionId;
    private String triggerCode;
    private String subjectType;
    private Object subjectId;
    private String variant;
    private String configKey;
    private Integer configVersion;
}
