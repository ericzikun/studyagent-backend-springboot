package com.studyagent.common.event;

import lombok.Getter;

/**
 * Agent 事件类型枚举
 */
@Getter
public enum AgentEventType {
    
    // ========== 任务级别事件 ==========
    
    TASK_STARTED("任务开始"),
    TASK_COMPLETED("任务完成"),
    TASK_FAILED("任务失败"),
    TASK_CANCELLED("任务取消"),
    TASK_PROGRESS("任务进度更新"),
    
    // ========== 子任务事件 ==========
    
    SUBTASK_CREATED("子任务创建"),
    SUBTASK_UPDATED("子任务更新"),
    
    // ========== Agent 事件 ==========
    
    AGENT_CREATED("Agent创建"),
    AGENT_OUTPUT("Agent输出"),
    AGENT_COMPLETED("Agent完成"),
    
    // ========== 活动日志事件 ==========
    
    ACTIVITY_LOG("活动日志"),
    
    // ========== 输出事件 ==========
    
    OUTPUT_CREATED("输出创建"),
    
    // ========== COMPOSE 事件 ==========
    
    COMPOSE_ROUND("COMPOSE轮次"),
    
    // ========== 批量事件 ==========
    
    BATCH_EVENTS("批量事件");
    
    private final String description;
    
    AgentEventType(String description) {
        this.description = description;
    }
    
    /**
     * 从字符串解析事件类型
     */
    public static AgentEventType fromString(String type) {
        try {
            return AgentEventType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
