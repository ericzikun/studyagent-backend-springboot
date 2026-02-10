package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量逻辑删除任务请求
 */
@Data
public class DeleteTasksRequest {
    @NotEmpty(message = "任务ID列表不能为空")
    private List<Long> taskIds;
}
