package com.studyagent.service.application.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评价任务请求（应用层）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateTaskRequest {
    private Long taskId;
    private Double score;
    private String content;
}

