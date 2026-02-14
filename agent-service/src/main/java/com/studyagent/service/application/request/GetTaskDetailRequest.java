package com.studyagent.service.application.request;

import lombok.Builder;
import lombok.Data;

/**
 * 获取任务详情请求
 */
@Data
@Builder
public class GetTaskDetailRequest {
    private Long taskId;
    private String clerkUserId;
}
