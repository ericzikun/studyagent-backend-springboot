package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 停止任务请求
 */
@Data
public class StopTaskRequest {
    @NotNull(message = "任务ID不能为空")
    private Long taskId;
}

