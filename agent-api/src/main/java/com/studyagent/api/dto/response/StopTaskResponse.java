package com.studyagent.api.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * 停止任务响应（与前端 Stop/Regenerate 约定对齐，字段同时以 snake_case 输出）
 */
@Data
@Builder
public class StopTaskResponse {
    @JsonProperty("task_id")
    private String taskId;

    /** 与 {@link com.studyagent.service.domain.task.TaskStatus} 数值一致，DRAFT=0 */
    @JsonProperty("task_status")
    private Integer taskStatus;

    /** 跳转 /create?draftId= 时使用，原地转草稿时与 task_id 相同 */
    @JsonProperty("editable_task_id")
    private String editableTaskId;

    @JsonProperty("workflow_available")
    private Boolean workflowAvailable;

    private String message;
}
