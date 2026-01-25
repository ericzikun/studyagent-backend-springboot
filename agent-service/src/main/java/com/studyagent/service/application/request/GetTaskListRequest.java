package com.studyagent.service.application.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询任务列表请求（应用层）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GetTaskListRequest {
    private String clerkUserId;
    private Integer status; // 任务状态（可选，0-全部）
    private String keyword; // 关键词（可选）
    private Integer order; // 排序方式：1-最新优先, 2-最旧优先, 3-标题A-Z, 4-标题Z-A, 5-更新时间最新优先
    private Integer pageNo; // 页码（从1开始）
    private Integer pageSize; // 每页大小
}

