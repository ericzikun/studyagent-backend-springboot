package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 任务详情查询请求
 * taskId 为 Sqids 编码后的对外 ID
 */
@Data
public class TaskDetailRequest {
    @NotBlank(message = "任务ID不能为空")
    private String taskId;
}

