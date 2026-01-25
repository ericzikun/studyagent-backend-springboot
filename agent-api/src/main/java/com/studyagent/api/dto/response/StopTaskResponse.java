package com.studyagent.api.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * 停止任务响应
 */
@Data
@Builder
public class StopTaskResponse {
    private Long taskId;
    private String message;
}

