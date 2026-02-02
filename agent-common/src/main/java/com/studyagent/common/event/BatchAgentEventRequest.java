package com.studyagent.common.event;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * 批量 Agent 事件请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchAgentEventRequest {
    
    /**
     * 关联的任务ID
     */
    private Long taskId;
    
    /**
     * 事件列表
     */
    private List<AgentEventRequest> events;
}
