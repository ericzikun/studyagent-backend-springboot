package com.studyagent.service.application.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 停止任务请求（应用层）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StopTaskRequest {
    private Long taskId;
    private String token;
}

