package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 任务详情查询请求
 */
@Data
public class TaskDetailRequest {
    @NotNull(message = "任务ID不能为空")
    private Long taskId;
}

