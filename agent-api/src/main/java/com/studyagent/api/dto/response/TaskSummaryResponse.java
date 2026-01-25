package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务汇总响应 DTO（驼峰命名，与前端保持一致）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskSummaryResponse {
    
    private Integer taskCompletedSize;
    
    private Integer taskInProgressSize;
    
    private Double avgQuality;
}

