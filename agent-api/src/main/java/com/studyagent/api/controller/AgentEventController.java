package com.studyagent.api.controller;

import com.studyagent.common.event.AgentEventRequest;
import com.studyagent.common.event.BatchAgentEventRequest;
import com.studyagent.common.event.AgentEventResponse;
import com.studyagent.api.service.AgentEventApplicationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 事件接收控制器
 * 
 * 接收来自 Python Agent 的事件消息，异步处理并更新数据库。
 * 替代原有的日志解析方案，实现更可靠的事件驱动架构。
 */
@RestController
@RequestMapping("/api/v1/agent-events")
@RequiredArgsConstructor
@Slf4j
public class AgentEventController {

    private final AgentEventApplicationService agentEventService;

    /**
     * 统一事件接收接口
     * 
     * POST /api/v1/agent-events
     * 
     * @param request 事件请求
     * @param agentToken 认证 Token（可选）
     * @return 事件处理响应
     */
    @PostMapping
    public ResponseEntity<AgentEventResponse> receiveEvent(
            @RequestBody @Valid AgentEventRequest request,
            @RequestHeader(value = "X-Agent-Token", required = false) String agentToken) {
        
        log.info("收到Agent事件: eventId={}, eventType={}, taskId={}", 
                request.getEventId(), request.getEventType(), request.getTaskId());
        
        try {
            // 1. 验证请求（可选，用于安全校验）
            // validateAgentToken(agentToken);
            
            // 2. 检查是否重复事件
            if (agentEventService.isDuplicateEvent(request.getEventId())) {
                log.warn("重复事件，已忽略: eventId={}", request.getEventId());
                return ResponseEntity.ok(AgentEventResponse.duplicate(request.getEventId()));
            }
            
            // 3. 异步处理事件
            agentEventService.processEventAsync(request);
            
            // 4. 立即返回确认
            return ResponseEntity.ok(AgentEventResponse.success(request.getEventId()));
            
        } catch (Exception e) {
            log.error("事件处理失败: eventId={}, error={}", request.getEventId(), e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(AgentEventResponse.error(request.getEventId(), e.getMessage()));
        }
    }

    /**
     * 批量事件接收接口（优化用）
     * 
     * POST /api/v1/agent-events/batch
     * 
     * @param request 批量事件请求
     * @return 批量事件处理响应
     */
    @PostMapping("/batch")
    public ResponseEntity<List<AgentEventResponse>> receiveBatchEvents(
            @RequestBody @Valid BatchAgentEventRequest request) {
        
        // 空指针保护
        if (request.getEvents() == null || request.getEvents().isEmpty()) {
            log.warn("批量事件为空: taskId={}", request.getTaskId());
            return ResponseEntity.ok(List.of());
        }
        
        log.info("收到批量Agent事件: taskId={}, count={}", 
                request.getTaskId(), request.getEvents().size());
        
        // 从外层获取 taskId，确保每个事件都有 taskId
        Long batchTaskId = request.getTaskId();
        
        List<AgentEventResponse> responses = request.getEvents().stream()
                .map(event -> {
                    try {
                        // 如果事件没有 taskId，使用批量请求的 taskId
                        if (event.getTaskId() == null && batchTaskId != null) {
                            event.setTaskId(batchTaskId);
                        }
                        
                        if (agentEventService.isDuplicateEvent(event.getEventId())) {
                            return AgentEventResponse.duplicate(event.getEventId());
                        }
                        agentEventService.processEventAsync(event);
                        return AgentEventResponse.success(event.getEventId());
                    } catch (Exception e) {
                        log.error("批量事件处理失败: eventId={}, error={}", 
                                event.getEventId(), e.getMessage());
                        return AgentEventResponse.error(event.getEventId(), e.getMessage());
                    }
                })
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(responses);
    }
    
    /**
     * 健康检查接口
     * 
     * GET /api/v1/agent-events/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Agent Event Service is running");
    }
}
