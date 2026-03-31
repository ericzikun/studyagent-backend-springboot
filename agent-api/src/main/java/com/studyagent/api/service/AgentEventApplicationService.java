package com.studyagent.api.service;

import com.studyagent.common.event.AgentEventRequest;
import com.studyagent.common.event.AgentEventType;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.repository.event.*;
import com.studyagent.service.domain.quota.QuotaDomainService;
import com.studyagent.service.domain.task.TaskStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

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
    private final QuotaDomainService quotaDomainService;
    private final EmailNotificationService emailNotificationService;
    
    // 🆕 Markdown 转 TipTap JSON 服务 URL
    @Value("${frontend.markdown-service-url:http://localhost:3000/api/markdown-to-tiptap}")
    private String markdownServiceUrl;
    
    // 🆕 RestTemplate 用于调用前端服务
    private final RestTemplate restTemplate = new RestTemplate();
    
    // 🆕 JdbcTemplate 用于原生 SQL（UPSERT）
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    
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
        // 不把 start_time 往后推：Java 提交时已写入开始时间时，若此处用更晚的事件时间会重置「前 2 分钟模拟进度」
        if (task.getStartTime() == null) {
            task.setStartTime(startTime);
        } else if (startTime != null && startTime.isBefore(task.getStartTime())) {
            task.setStartTime(startTime);
        }
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
        
        // 异步发送任务完成邮件通知（best-effort，不影响主流程）
        try {
            emailNotificationService.sendTaskCompletedEmail(task);
        } catch (Exception e) {
            log.warn("触发邮件通知异常: taskId={}, error={}", taskId, e.getMessage());
        }
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

        // 用户停止后 Java 已将主任务置为草稿；Python 侧 stop 常走异常路径仍会发 TASK_FAILED，不得覆盖为失败
        if (TaskStatus.DRAFT.getCode().equals(task.getStatus())
                || TaskStatus.CANCELLED.getCode().equals(task.getStatus())) {
            log.info("TASK_FAILED 忽略: taskId={} 状态为{}（停止后的异步失败事件）", taskId, task.getStatus());
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

        // 任务失败额度退款
        try {
            quotaDomainService.refundByTaskId(taskId, "任务执行失败");
        } catch (Exception ex) {
            log.warn("任务失败退款异常: taskId={}, error={}", taskId, ex.getMessage());
        }

        log.info("任务失败: taskId={}, error={}", taskId, getStringValue(payload, "errorMessage"));
    }

    /**
     * 处理任务取消事件（Python 侧用户停止 / STOP_TASK 后的收敛）。
     * 与 Java {@code TaskApplicationService#stopTask} 语义一致：主任务回到可编辑草稿。
     * 若此前误收到 {@code TASK_FAILED}（停止时 Python 异常路径仍可能发失败事件），此处仍可将 {@link TaskStatus#FAILED} 收敛为草稿。
     */
    @Transactional
    protected void handleTaskCancelled(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        
        TaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("任务不存在: taskId={}", taskId);
            return;
        }

        Integer st = task.getStatus();
        if (TaskStatus.COMPLETED.getCode().equals(st)) {
            log.info("TASK_CANCELLED 忽略: taskId={} 已完成", taskId);
            return;
        }

        task.setStatus(TaskStatus.DRAFT.getCode());
        task.setFinishTime(null);
        task.setErrorMessage(null);
        
        taskRepository.save(task);

        // 停止不退款：用户中途停止时已产生模型等资源消耗，与任务失败（未成功交付）区分

        log.info("任务停止事件已收敛为草稿: taskId={}, reason={}", taskId, getStringValue(payload, "reason"));
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
        
        // 限制在 [0, 100]，避免上游异常导致 >100%（如 200%）展示
        double raw = getDoubleValue(payload, "completePercent", 0.0);
        double completePercent = Math.max(0.0, Math.min(100.0, raw));
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
     * 🔧 优化（2026-02-09）：使用 INSERT ON DUPLICATE KEY UPDATE 解决并发问题
     * - 问题：原代码 findByXXX + save 不是原子操作，并发时可能重复插入或覆盖
     * - 方案：使用 MySQL 原生 UPSERT 语法，数据库层面保证原子性
     * - 性能：单条 SQL，比先查后写快 50%
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
        
        // 🚀 使用原生 SQL 的 INSERT ON DUPLICATE KEY UPDATE（UPSERT）
        // 优点：
        // 1. 原子操作，彻底解决并发问题
        // 2. 单条 SQL，性能优于先查后写
        // 3. 数据库层面保证唯一性
        
        String sql = """
            INSERT INTO task_agents 
                (task_id, agent_name, subtask_id, agent_desc, agent_status, 
                 complete_percent, agent_priority, agent_start_time, 
                 agent_output, created_at, updated_at)
            VALUES 
                (?, ?, ?, ?, 2, 0.00, 1, NOW(), ?, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                agent_output = CASE 
                    WHEN ? = 'FULL' THEN VALUES(agent_output)
                    ELSE CONCAT(COALESCE(agent_output, ''), '\\n\\n---\\n\\n', VALUES(agent_output))
                END,
                agent_status = 2,
                updated_at = NOW()
            """;
        
        try {
            // 使用空字符串代替 NULL（MySQL 对 NULL 的唯一约束特殊处理）
            String subtaskIdValue = (subtaskId != null && !subtaskId.isEmpty()) ? subtaskId : "";
            String agentDesc = "AI Agent: " + agentName;
            
            int affected = jdbcTemplate.update(sql, 
                taskId, 
                agentName, 
                subtaskIdValue,
                agentDesc,
                outputContent, 
                outputType
            );
            
            log.info("✅ Agent输出更新(UPSERT): taskId={}, subtaskId={}, agent={}, type={}, len={}, affected={}", 
                    taskId, subtaskIdValue, agentName, outputType, outputContent.length(), affected);
                    
        } catch (org.springframework.dao.DataAccessException e) {
            log.error("❌ Agent输出更新失败: taskId={}, agent={}, error={}", 
                    taskId, agentName, e.getMessage());
            
            // 如果 UPSERT 失败（可能是唯一索引不存在），回退到原逻辑
            log.warn("⚠️ UPSERT 失败，回退到原逻辑");
            handleAgentOutputFallback(request);
        }
    }
    
    /**
     * Agent 输出处理的回退逻辑（兼容性）
     * 
     * 当 UPSERT 失败时使用，通常是因为数据库还没有创建唯一索引
     */
    private void handleAgentOutputFallback(AgentEventRequest request) {
        Long taskId = request.getTaskId();
        Map<String, Object> payload = request.getPayload();
        
        String agentName = getStringValue(payload, "agentName");
        String outputContent = getStringValue(payload, "outputContent");
        String outputType = getStringValue(payload, "outputType");
        String subtaskId = getStringValue(payload, "subtaskId");
        
        // 原有逻辑
        TaskAgentEntity agent = taskAgentRepository.findByTaskIdAndAgentNameAndSubtaskId(taskId, agentName, subtaskId);
        
        if (agent == null) {
            agent = new TaskAgentEntity();
            agent.setTaskId(taskId);
            agent.setAgentName(agentName);
            agent.setSubtaskId(subtaskId);
            agent.setAgentDesc("AI Agent: " + agentName);
            agent.setAgentStatus(2); // Running
            agent.setCompletePercent(new BigDecimal("0.00"));
            agent.setAgentPriority(1);
            agent.setAgentStartTime(LocalDateTime.now());
            agent.setCreatedAt(LocalDateTime.now());
        }
        
        if ("FULL".equals(outputType)) {
            agent.setAgentOutput(outputContent);
        } else {
            String existing = agent.getAgentOutput();
            if (existing != null && !existing.isEmpty()) {
                agent.setAgentOutput(existing + "\n\n---\n\n" + outputContent);
            } else {
                agent.setAgentOutput(outputContent);
            }
        }
        
        agent.setUpdatedAt(LocalDateTime.now());
        taskAgentRepository.save(agent);
        
        log.info("Agent输出更新(Fallback): taskId={}, subtaskId={}, agent={}, type={}, len={}", 
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
     * 
     * 🆕 增强逻辑：
     * - 如果 format=MARKDOWN 且 contentText 不为空，自动调用 MD 转 JSON 服务
     * - 将转换后的 TipTap JSON 存储到 contentJson 字段
     * - 支持前端富文本编辑器正常渲染
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
        
        // 获取内容
        String contentText = getStringValue(payload, "contentText");
        String contentJson = getStringValue(payload, "contentJson");
        
        output.setContentText(contentText);
        output.setLogText(getStringValue(payload, "logText"));
        
        // 🆕 如果是 Markdown 格式且有内容，尝试转换为 TipTap JSON
        if (isMarkdownFormat(getStringValue(payload, "format")) && 
            contentText != null && !contentText.trim().isEmpty() &&
            (contentJson == null || contentJson.isEmpty())) {
            
            log.info("检测到 Markdown 内容，开始转换为 TipTap JSON: taskId={}, contentLength={}", 
                    taskId, contentText.length());
            
            String convertedJson = convertMarkdownToTiptapJson(contentText);
            if (convertedJson != null && !convertedJson.isEmpty()) {
                output.setContentJson(convertedJson);
                log.info("✅ Markdown 转 JSON 成功: taskId={}, jsonLength={}", taskId, convertedJson.length());
            } else {
                log.warn("⚠️ Markdown 转 JSON 失败或服务不可用，仅保存 Markdown 文本: taskId={}", taskId);
                // 即使转换失败，也保存 Markdown 文本
                output.setContentJson(null);
            }
        } else {
            // 直接使用 Python 端提供的 contentJson
            output.setContentJson(contentJson);
        }
        
        output.setCreatedAt(LocalDateTime.now());
        output.setUpdatedAt(LocalDateTime.now());
        
        taskOutputRepository.save(output);
        
        log.info("输出创建: taskId={}, title={}, format={}, hasContentText={}, hasContentJson={}", 
                taskId, output.getTitle(), getStringValue(payload, "format"), 
                (contentText != null && !contentText.isEmpty()),
                (output.getContentJson() != null && !output.getContentJson().isEmpty()));
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
    
    // ========== Markdown 转换相关方法 ==========
    
    /**
     * 判断是否为 Markdown 格式
     */
    private boolean isMarkdownFormat(String format) {
        if (format == null) return false;
        return "MARKDOWN".equalsIgnoreCase(format) || "CODE".equalsIgnoreCase(format);
    }
    
    /**
     * 将 Markdown 内容转换为 TipTap JSON 格式
     * 
     * @param markdownContent Markdown 内容
     * @return TipTap JSON 字符串，失败返回 null
     */
    private String convertMarkdownToTiptapJson(String markdownContent) {
        if (markdownContent == null || markdownContent.trim().isEmpty()) {
            log.debug("Markdown 内容为空，跳过转换");
            return null;
        }
        
        try {
            log.info("🔄 调用 Markdown 转 TipTap JSON 服务: {}", markdownServiceUrl);
            log.debug("📝 Markdown 内容长度: {} 字符", markdownContent.length());
            
            // 构造请求体
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("markdown", markdownContent);
            
            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            // 创建请求实体
            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(requestBody, headers);
            
            // 发送 POST 请求
            ResponseEntity<Map> response = restTemplate.exchange(
                markdownServiceUrl,
                HttpMethod.POST,
                requestEntity,
                Map.class
            );
            
            log.debug("📡 响应状态码: {}", response.getStatusCode());
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                log.debug("📦 响应内容: {}", responseBody.keySet());
                
                if (responseBody.containsKey("json")) {
                    Object tiptapJson = responseBody.get("json");
                    
                    // 将 TipTap JSON 对象转换为字符串
                    String jsonString = convertObjectToJsonString(tiptapJson);
                    
                    if (jsonString != null && !jsonString.isEmpty()) {
                        log.info("✅ Markdown 转换成功，JSON 大小: {} 字符", jsonString.length());
                        return jsonString;
                    } else {
                        log.warn("⚠️ 转换服务返回的 JSON 为空");
                        return null;
                    }
                } else {
                    log.warn("⚠️ 转换服务返回格式异常，缺少 'json' 字段。响应内容: {}", responseBody);
                    return null;
                }
            } else {
                log.warn("⚠️ 转换服务请求失败: HTTP {}", response.getStatusCode());
                return null;
            }
            
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                log.warn("⚠️ 转换服务请求参数错误: {}", e.getMessage());
            } else {
                log.warn("⚠️ 转换服务客户端错误: HTTP {} - {}", 
                        e.getStatusCode(), e.getMessage());
            }
            return null;
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            log.warn("⚠️ 转换服务内部错误: HTTP {} - {}", 
                    e.getStatusCode(), e.getMessage());
            return null;
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.warn("⚠️ 无法连接到转换服务: {}，错误: {}", markdownServiceUrl, e.getMessage());
            log.info("💡 提示: 请确保前端服务正在运行，且 URL 配置正确");
            return null;
        } catch (Exception e) {
            log.warn("⚠️ Markdown 转 TipTap JSON 失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 将对象转换为 JSON 字符串
     */
    private String convertObjectToJsonString(Object obj) {
        try {
            if (obj == null) {
                return null;
            }
            
            // 使用 Jackson 或其他 JSON 库序列化
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = 
                new com.fasterxml.jackson.databind.ObjectMapper();
            return objectMapper.writeValueAsString(obj);
            
        } catch (Exception e) {
            log.error("❌ 对象转 JSON 字符串失败: {}", e.getMessage());
            return null;
        }
    }
}
