package com.studyagent.api.dto.request;

import lombok.Data;

/**
 * 任务列表查询请求
 */
@Data
public class TaskListRequest {
    private String taskKeyword;
    private Integer taskStatus; // 0-未知（全部）, 1-草稿, 2-待执行, 3-执行中, 4-已完成, 5-失败
    private Integer order; // 1-最新优先, 2-最旧优先, 3-标题A-Z, 4-标题Z-A, 5-最高质量优先
    private Integer pageNo = 1;
    private Integer pageSize = 10;
}

