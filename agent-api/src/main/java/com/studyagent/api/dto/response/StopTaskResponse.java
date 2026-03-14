package com.studyagent.api.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * 停止任务响应
 */
@Data
@Builder
public class StopTaskResponse {
    /** 对外暴露的 taskId（Sqids 编码） */
    private String taskId;
    private String message;
}

