package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务列表响应 DTO（驼峰命名，与前端保持一致）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskListResponse {
    
    private List<TaskListItemResponse> taskList;
    
    private TaskSummaryResponse taskSummary;
    
    private Integer total;
    
    private Integer pageNo;
    
    private Integer pageSize;
}

