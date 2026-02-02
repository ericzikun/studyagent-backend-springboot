package com.studyagent.common.event;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Agent 事件请求 DTO
 * 
 * 用于接收来自 Python Agent 的事件消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEventRequest {
    
    /**
     * 事件唯一ID，用于去重和追踪
     */
    private String eventId;
    
    /**
     * 事件类型
     */
    private String eventType;
    
    /**
     * 关联的任务ID
     */
    private Long taskId;
    
    /**
     * 事件发生时间 (ISO8601格式)
     */
    private Instant timestamp;
    
    /**
     * 事件载荷，根据 eventType 不同而变化
     */
    private Map<String, Object> payload;
}
