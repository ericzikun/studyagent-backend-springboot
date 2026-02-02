package com.studyagent.common.event;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Agent 事件响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEventResponse {
    
    /**
     * 事件ID
     */
    private String eventId;
    
    /**
     * 处理状态: SUCCESS | DUPLICATE | ERROR
     */
    private String status;
    
    /**
     * 消息
     */
    private String message;
    
    /**
     * 创建成功响应
     */
    public static AgentEventResponse success(String eventId) {
        return AgentEventResponse.builder()
                .eventId(eventId)
                .status("SUCCESS")
                .message("Event received and queued for processing")
                .build();
    }
    
    /**
     * 创建重复事件响应
     */
    public static AgentEventResponse duplicate(String eventId) {
        return AgentEventResponse.builder()
                .eventId(eventId)
                .status("DUPLICATE")
                .message("Event already processed")
                .build();
    }
    
    /**
     * 创建错误响应
     */
    public static AgentEventResponse error(String eventId, String message) {
        return AgentEventResponse.builder()
                .eventId(eventId)
                .status("ERROR")
                .message(message)
                .build();
    }
}
