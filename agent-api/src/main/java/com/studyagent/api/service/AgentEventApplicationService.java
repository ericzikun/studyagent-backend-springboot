package com.studyagent.api.service;

import com.studyagent.common.event.AgentEventRequest;
import com.studyagent.common.event.AgentEventType;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.repository.event.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Agent 事件应用服务
 * 
 * 负责异步处理来自 Python Agent 的事件消息，更新数据库状态。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentEventApplicationService {

    private final TaskEntityRepository taskRepository;
    private final SubTaskEntityRepository subTaskRepository;
    private final TaskAgentEntityRepository taskAgentRepository;
    private final TaskActivityEntityRepository taskActivityRepository;
    private final TaskOutputEntityRepository taskOutputRepository;
    
    /**
     * 简单的内存去重缓存（生产环境建议使用 Redis）
     * Key: eventId, Value: 过期时间戳
     */
    private final ConcurrentHashMap<String, Long> processedEvents = new ConcurrentHashMap<>();
    
    /**
     * 事件过期时间（24小时）
     */
    private static final long EVENT_EXPIRE_MS = TimeUnit.HOURS.toMillis(24);
    
    /**
     * 检查事件是否已处理（幂等性检查）
     */
    public boolean isDuplicateEvent(String eventId) {
        if (eventId == null) {
            return false;
        }
        
        // 清理过期的事件ID
        long now = System.currentTimeMillis();
        processedEvents.entrySet().removeIf(entry -> entry.getValue() < now);
        
        return processedEvents.containsKey(eventId);
    }
    
    /**
     * 标记事件已处理
     */
    private void markEventProcessed(String eventId) {
        if (eventId != null) {
            processedEvents.put(eventId, System.currentTimeMillis() + EVENT_EXPIRE_MS);
        }
    }

    /**
     * 异步处理事件
     */
    @Async("agentEventExecutor")
    public void processEventAsync(AgentEventRequest request) {
        try {
            // 再次检查幂等性（防止并发）
            if (isDuplicateEvent(request.getEventId())) {
                log.debug("跳过重复事件: eventId={}", request.getEventId());
                return;
            }
            
            // 解析事件类型
            AgentEventType eventType = AgentEventType.fromString(request.getEventType());
            if (eventType == null) {
                log.warn("未知事件类型: {}", request.getEventType());
                return;
            }
            
            // 根据事件类型分发处理
            switch (eventType) {
                case TASK_STARTED:
                    handleTaskStarted(request);
                    break;
                case TASK_COMPLETED:
                    handleTaskCompleted(request);
                    break;
                case TASK_FAILED:
                    handleTaskFailed(request);
                    break;
                case TASK_CANCELLED:
                    handleTaskCancelled(request);
                    break;
                case TASK_PROGRESS:
                    handleTaskProgress(request);
                    break;
                case SUBTASK_CREATED:
                    handleSubtaskCreated(request);
                    break;
                case SUBTASK_UPDATED:
                    handleSubtaskUpdated(request);
                    break;
                case AGENT_CREATED:
                    handleAgentCreated(request);
                    break;
                case AGENT_OUTPUT:
                    handleAgentOutput(request);
                    break;
                case AGENT_COMPLETED:
                    handleAgentCompleted(request);
                    break;
                case ACTIVITY_LOG:
                    handleActivityLog(request);
                    break;
                case OUTPUT_CREATED:
                    handleOutputCreated(request);
                    break;
                case COMPOSE_ROUND:
                    handleComposeRound(request);
                    break;
                case BATCH_EVENTS:
                    handleBatchEvents(request);
                    break;
                default:
                    log.warn("未处理的事件类型: {}", eventType);
            }
            
            // 标记事件已处理
            markEventProcessed(request.getEventId());
            
            log.info("事件处理完成: eventId={}, eventType={}", 
                    request.getEventId(), request.getEventType());
                    
        } catch (Exception e) {
            log.error("事件处理异常: eventId={}, error={}", 
                    request.getEventId(), e.getMessage(), e);
        }
    }
    
    // ========== 事件处理方法 ==========

    /**
     * 处理任务开始事件
     */
    @Transactional
    protected void handleTaskStarted(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        LocalDateTime startTime = toLocalDateTime(request.getTimestamp());
        
        TaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("任务不存在: taskId={}", taskId);
            return;
        }
        
        task.setStatus(2); // InProgress
        task.setStartTime(startTime);
        taskRepository.save(task);
        
        log.info("任务开始: taskId={}", taskId);
    }

    /**
     * 处理任务完成事件
     */
    @Transactional
    protected void handleTaskCompleted(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        LocalDateTime finishTime = toLocalDateTime(request.getTimestamp());
        
        TaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("任务不存在: taskId={}", taskId);
            return;
        }
        
        // 更新任务
        task.setStatus(3); // Completed
        task.setFinishTime(finishTime);
        task.setCostTime(getIntValue(payload, "costTimeSeconds", 0));
        task.setCompletePercent(new BigDecimal("100.00"));
        
        String requirementJson = getStringValue(payload, "requirementJson");
        if (requirementJson != null) {
            task.setRequirementJson(requirementJson);
        }
        
        String finalResult = getStringValue(payload, "finalResult");
        if (finalResult != null) {
            task.setFinalResult(finalResult);
        }
        
        taskRepository.save(task);
        
        // 批量更新子任务状态
        subTaskRepository.updateStatusByTaskId(taskId, 2, "Completed"); // Completed
        
        // 批量更新 Agent 状态
        taskAgentRepository.completeAllByTaskId(taskId, finishTime);
        
        log.info("任务完成: taskId={}, costTime={}s", taskId, task.getCostTime());
    }

    /**
     * 处理任务失败事件
     */
    @Transactional
    protected void handleTaskFailed(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        LocalDateTime finishTime = toLocalDateTime(request.getTimestamp());
        
        TaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("任务不存在: taskId={}", taskId);
            return;
        }
        
        // 更新任务
        task.setStatus(4); // Failed
        task.setFinishTime(finishTime);
        if (task.getStartTime() != null) {
            long seconds = java.time.Duration.between(task.getStartTime(), finishTime).getSeconds();
            task.setCostTime((int) seconds);
        }
        task.setErrorMessage(getStringValue(payload, "errorMessage"));
        
        taskRepository.save(task);
        
        // 批量更新未完成的子任务状态为失败
        subTaskRepository.updatePendingStatusByTaskId(taskId, 3, "执行失败"); // Failed
        
        // 批量更新未完成的 Agent 状态为失败
        taskAgentRepository.failPendingByTaskId(taskId);
        
        log.info("任务失败: taskId={}, error={}", taskId, getStringValue(payload, "errorMessage"));
    }

    /**
     * 处理任务取消事件
     */
    @Transactional
    protected void handleTaskCancelled(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        LocalDateTime finishTime = toLocalDateTime(request.getTimestamp());
        
        TaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("任务不存在: taskId={}", taskId);
            return;
        }
        
        task.setStatus(5); // Cancelled
        task.setFinishTime(finishTime);
        task.setErrorMessage(getStringValue(payload, "reason"));
        
        taskRepository.save(task);
        
        log.info("任务取消: taskId={}", taskId);
    }

    /**
     * 处理任务进度更新事件
     */
    @Transactional
    protected void handleTaskProgress(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        
        TaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("任务不存在: taskId={}", taskId);
            return;
        }
        
        Double completePercent = getDoubleValue(payload, "completePercent", 0.0);
        task.setCompletePercent(new BigDecimal(String.valueOf(completePercent)));
        task.setTaskCompletedSize(getIntValue(payload, "completedSubtaskCount", 0));
        task.setActiveAgentSize(getIntValue(payload, "activeAgentCount", 0));
        task.setEstRemainingTime(getIntValue(payload, "estRemainingTimeSeconds", 0));
        
        taskRepository.save(task);
        
        log.debug("进度更新: taskId={}, progress={}%", taskId, completePercent);
    }

    /**
     * 处理子任务创建事件
     */
    @Transactional
    protected void handleSubtaskCreated(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        
        String title = getStringValue(payload, "title");
        String subtaskId = getStringValue(payload, "subtaskId"); // 🆕 Python端生成的子任务ID
        
        if (title == null || title.isEmpty()) {
            log.warn("子任务标题为空，跳过: taskId={}", taskId);
            return;
        }
        
        // 检查是否已存在相同子任务（避免重复）
        if (subTaskRepository.existsByTaskIdAndTitleLike(taskId, title.substring(0, Math.min(50, title.length())))) {
            log.debug("子任务已存在，跳过: taskId={}, title={}", taskId, title);
            return;
        }
        
        SubTaskEntity subtask = new SubTaskEntity();
        subtask.setTaskId(taskId);
        subtask.setSubtaskCode(subtaskId); // 🆕 存储 Python端的子任务ID
        subtask.setTitle(title.length() > 255 ? title.substring(0, 255) : title);
        subtask.setDescription(getStringValue(payload, "description"));
        subtask.setStatus(0); // Pending
        subtask.setOrderIndex(getIntValue(payload, "orderIndex", 0));
        subtask.setAgentName(getStringValue(payload, "assignedAgentName"));
        subtask.setProcessDesc("Pending");
        subtask.setCreatedAt(LocalDateTime.now());
        subtask.setUpdatedAt(LocalDateTime.now());
        
        subTaskRepository.save(subtask);
        
        log.info("子任务创建: taskId={}, subtaskCode={}, title={}", taskId, subtaskId, title.substring(0, Math.min(50, title.length())));
    }

    /**
     * 处理子任务状态更新事件
     */
    @Transactional
    protected void handleSubtaskUpdated(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        
        String subtaskId = getStringValue(payload, "subtaskId");
        String statusStr = getStringValue(payload, "status");
        
        int status = parseSubtaskStatus(statusStr);
        
        // 通过 subtaskId 或标题匹配更新
        subTaskRepository.updateByTaskIdAndSubtaskId(
                taskId, 
                subtaskId,
                status,
                getStringValue(payload, "processDesc"),
                getStringValue(payload, "agentName")
        );
        
        log.info("子任务更新: taskId={}, subtaskId={}, status={}", taskId, subtaskId, statusStr);
    }

    /**
     * 处理 Agent 创建事件
     */
    @Transactional
    protected void handleAgentCreated(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        LocalDateTime startTime = toLocalDateTime(request.getTimestamp());
        
        String agentName = getStringValue(payload, "agentName");
        if (agentName == null || agentName.isEmpty()) {
            log.warn("Agent名称为空，跳过: taskId={}", taskId);
            return;
        }
        
        // 检查是否已存在（upsert 逻辑）
        TaskAgentEntity existing = taskAgentRepository.findByTaskIdAndAgentName(taskId, agentName);
        
        if (existing == null) {
            TaskAgentEntity agent = new TaskAgentEntity();
            agent.setTaskId(taskId);
            agent.setAgentName(agentName);
            agent.setAgentDesc(getStringValue(payload, "agentDesc"));
            agent.setAgentStatus(2); // Running
            agent.setCompletePercent(new BigDecimal("0.00"));
            agent.setAgentPriority(getIntValue(payload, "priority", 1));
            agent.setAgentStartTime(startTime);
            agent.setCreatedAt(LocalDateTime.now());
            agent.setUpdatedAt(LocalDateTime.now());
            
            taskAgentRepository.save(agent);
            log.info("Agent创建: taskId={}, agent={}", taskId, agentName);
        } else {
            // 更新状态为运行中
            if (existing.getAgentStatus() != 3) { // 不是已完成状态
                existing.setAgentStatus(2); // Running
                existing.setUpdatedAt(LocalDateTime.now());
                taskAgentRepository.save(existing);
            }
            log.debug("Agent已存在，更新状态: taskId={}, agent={}", taskId, agentName);
        }
    }

    /**
     * 处理 Agent 输出事件
     * 
     * 🆕 使用 (taskId, agentName, subtaskId) 三元组唯一标识一条 Agent 输出记录
     * 解决同一 Agent 类型处理多个子任务时输出被覆盖的问题
     */
    @Transactional
    protected void handleAgentOutput(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        
        String agentName = getStringValue(payload, "agentName");
        String outputContent = getStringValue(payload, "outputContent");
        String outputType = getStringValue(payload, "outputType");
        String subtaskId = getStringValue(payload, "subtaskId"); // 🆕 获取子任务ID
        
        if (agentName == null || outputContent == null) {
            log.warn("Agent输出参数不完整，跳过: taskId={}", taskId);
            return;
        }
        
        // 🆕 使用三元组 (taskId, agentName, subtaskId) 查找
        TaskAgentEntity agent = taskAgentRepository.findByTaskIdAndAgentNameAndSubtaskId(taskId, agentName, subtaskId);
        
        if (agent == null) {
            // 如果不存在则创建新记录
            agent = new TaskAgentEntity();
            agent.setTaskId(taskId);
            agent.setAgentName(agentName);
            agent.setSubtaskId(subtaskId); // 🆕 设置子任务ID
            agent.setAgentDesc("AI Agent: " + agentName);
            agent.setAgentStatus(2); // Running
            agent.setCompletePercent(new BigDecimal("0.00"));
            agent.setAgentPriority(1);
            agent.setAgentStartTime(LocalDateTime.now());
            agent.setCreatedAt(LocalDateTime.now());
        }
        
        // 根据 outputType 决定替换还是追加
        if ("FULL".equals(outputType)) {
            agent.setAgentOutput(outputContent);
        } else {
            // APPEND 模式
            String existing = agent.getAgentOutput();
            if (existing != null && !existing.isEmpty()) {
                agent.setAgentOutput(existing + "\n\n---\n\n" + outputContent);
            } else {
                agent.setAgentOutput(outputContent);
            }
        }
        
        agent.setUpdatedAt(LocalDateTime.now());
        taskAgentRepository.save(agent);
        
        log.info("Agent输出更新: taskId={}, subtaskId={}, agent={}, type={}, len={}", 
                taskId, subtaskId, agentName, outputType, outputContent.length());
    }

    /**
     * 处理 Agent 完成事件
     */
    @Transactional
    protected void handleAgentCompleted(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        LocalDateTime finishTime = toLocalDateTime(request.getTimestamp());
        
        String agentName = getStringValue(payload, "agentName");
        
        TaskAgentEntity agent = taskAgentRepository.findByTaskIdAndAgentName(taskId, agentName);
        if (agent != null) {
            agent.setAgentStatus(3); // Completed
            agent.setCompletePercent(new BigDecimal("100.00"));
            agent.setAgentFinishTime(finishTime);
            agent.setUpdatedAt(LocalDateTime.now());
            
            String finalOutput = getStringValue(payload, "finalOutput");
            if (finalOutput != null) {
                agent.setAgentOutput(finalOutput);
            }
            
            taskAgentRepository.save(agent);
            log.info("Agent完成: taskId={}, agent={}", taskId, agentName);
        }
    }

    /**
     * 处理活动日志事件
     */
    @Transactional
    protected void handleActivityLog(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        LocalDateTime activityTime = toLocalDateTime(request.getTimestamp());
        
        TaskActivityEntity activity = new TaskActivityEntity();
        activity.setTaskId(taskId);
        activity.setActivityTime(activityTime);
        activity.setAgentName(getStringValue(payload, "agentName"));
        activity.setActivityDesc(getStringValue(payload, "activityDesc"));
        activity.setActivityDetail(getStringValue(payload, "activityDetail"));
        activity.setCreatedAt(LocalDateTime.now());
        
        taskActivityRepository.save(activity);
        
        log.debug("活动日志: taskId={}, type={}", taskId, getStringValue(payload, "activityType"));
    }

    /**
     * 处理输出创建事件
     */
    @Transactional
    protected void handleOutputCreated(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        
        TaskOutputEntity output = new TaskOutputEntity();
        output.setTaskId(taskId);
        output.setTitle(getStringValue(payload, "title"));
        output.setDescription(getStringValue(payload, "description"));
        output.setFilePath(getStringValue(payload, "filePath", ""));
        output.setDownloadUrl(getStringValue(payload, "downloadUrl", ""));
        output.setSizeDesc(formatSize(getLongValue(payload, "sizeBytes", 0L)));
        output.setPageSize(getIntValue(payload, "pageCount", 0));
        output.setFormat(parseFormat(getStringValue(payload, "format")));
        output.setOutputType(parseOutputType(getStringValue(payload, "outputType")));
        output.setContentText(getStringValue(payload, "contentText"));
        output.setContentJson(getStringValue(payload, "contentJson"));
        output.setLogText(getStringValue(payload, "logText"));
        output.setCreatedAt(LocalDateTime.now());
        output.setUpdatedAt(LocalDateTime.now());
        
        taskOutputRepository.save(output);
        
        log.info("输出创建: taskId={}, title={}", taskId, output.getTitle());
    }

    /**
     * 处理 COMPOSE 轮次事件
     */
    @Transactional
    protected void handleComposeRound(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        LocalDateTime activityTime = toLocalDateTime(request.getTimestamp());
        
        int currentRound = getIntValue(payload, "currentRound", 0);
        int totalRounds = getIntValue(payload, "totalRounds", 0);
        String roundContent = getStringValue(payload, "roundContent");
        
        // 记录活动日志
        TaskActivityEntity activity = new TaskActivityEntity();
        activity.setTaskId(taskId);
        activity.setActivityTime(activityTime);
        activity.setAgentName("系统");
        activity.setActivityDesc(String.format("[合成] TASK COMPOSE 第%d轮完成，共%d轮", currentRound, totalRounds));
        activity.setCreatedAt(LocalDateTime.now());
        
        taskActivityRepository.save(activity);
        
        // 追加到最后一个 Agent 的输出（如果有）
        if (roundContent != null && !roundContent.isEmpty()) {
            TaskAgentEntity lastAgent = taskAgentRepository.findTopByTaskIdOrderByAgentStartTimeDesc(taskId);
            if (lastAgent != null) {
                String section = String.format("\n\n%s\nTASK COMPOSE 第%d轮，共%d轮\n%s\n%s",
                        "=".repeat(60), currentRound, totalRounds, "=".repeat(60), roundContent);
                
                String existing = lastAgent.getAgentOutput();
                if (existing != null) {
                    lastAgent.setAgentOutput(existing + section);
                } else {
                    lastAgent.setAgentOutput(section);
                }
                lastAgent.setUpdatedAt(LocalDateTime.now());
                taskAgentRepository.save(lastAgent);
            }
        }
        
        log.info("COMPOSE轮次: taskId={}, round={}/{}", taskId, currentRound, totalRounds);
    }

    /**
     * 处理批量事件
     */
    @SuppressWarnings("unchecked")
    protected void handleBatchEvents(AgentEventRequest request) {
        Map<String, Object> payload = request.getPayload();
        
        List<Map<String, Object>> events = (List<Map<String, Object>>) payload.get("events");
        
        if (events == null) {
            log.warn("批量事件为空: eventId={}", request.getEventId());
            return;
        }
        
        for (Map<String, Object> eventData : events) {
            try {
                AgentEventRequest subRequest = AgentEventRequest.builder()
                        .eventId((String) eventData.get("eventId"))
                        .eventType((String) eventData.get("eventType"))
                        .taskId(request.getTaskId())
                        .timestamp(request.getTimestamp())
                        .payload((Map<String, Object>) eventData.get("payload"))
                        .build();
                
                processEventAsync(subRequest);
            } catch (Exception e) {
                log.error("批量事件子项处理失败: {}", e.getMessage());
            }
        }
    }
    
    // ========== 辅助方法 ==========
    
    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
    
    private String getStringValue(Map<String, Object> map, String key) {
        return getStringValue(map, key, null);
    }
    
    private String getStringValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
    
    private int getIntValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
    
    private double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
    
    private long getLongValue(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }
    
    private int parseSubtaskStatus(String status) {
        if (status == null) return 0;
        switch (status.toUpperCase()) {
            case "PENDING": return 0;
            case "IN_PROGRESS": return 1;
            case "COMPLETED": return 2;
            case "FAILED": return 3;
            default: return 0;
        }
    }
    
    private int parseFormat(String format) {
        if (format == null) return 4;
        switch (format.toUpperCase()) {
            case "WORD": return 1;
            case "PDF": return 2;
            case "PPT": return 3;
            case "MARKDOWN":
            case "CODE": return 4;
            default: return 4;
        }
    }
    
    private int parseOutputType(String outputType) {
        if (outputType == null) return 0;
        switch (outputType.toUpperCase()) {
            case "DRAFT": return 0;
            case "FINAL": return 1;
            case "ATTACHMENT": return 2;
            default: return 0;
        }
    }
    
    private String formatSize(long sizeBytes) {
        if (sizeBytes < 1024) return sizeBytes + "B";
        if (sizeBytes < 1024 * 1024) return String.format("%.1fKB", sizeBytes / 1024.0);
        if (sizeBytes < 1024 * 1024 * 1024) return String.format("%.1fMB", sizeBytes / (1024.0 * 1024));
        return String.format("%.1fGB", sizeBytes / (1024.0 * 1024 * 1024));
    }
}
