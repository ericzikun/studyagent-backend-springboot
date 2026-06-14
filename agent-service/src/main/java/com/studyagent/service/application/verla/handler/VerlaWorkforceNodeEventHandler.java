package com.studyagent.service.application.verla.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.payload.VerlaWorkforceNodeDetailedPayload;
import com.studyagent.common.verla.envelope.payload.VerlaWorkforceNodeUpdatedPayload;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaWorkforceTask;
import com.studyagent.service.domain.verla.VerlaWorkforceTaskOutput;
import com.studyagent.service.domain.verla.repo.VerlaWorkforceTaskOutputRepository;
import com.studyagent.service.domain.verla.repo.VerlaWorkforceTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Verla Workforce 任务节点持久化 handler。
 * <p>
 * 支持两类事件：
 * <ul>
 *   <li>{@link VerlaAgentEventType#ASSIGNMENT_AGENT_NODE_UPDATED} →
 *       upsert {@code verla_workforce_tasks}（plan / task 节点状态快照）</li>
 *   <li>{@link VerlaAgentEventType#ASSIGNMENT_AGENT_NODE_DETAILED} →
 *       upsert {@code verla_workforce_task_outputs}（产出文本追加 + detailChunk 合并）</li>
 * </ul>
 * 两者均按 (session_id, node_id) 幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaWorkforceNodeEventHandler implements VerlaEventHandler {

    private static final String NODE_KIND_PLAN    = "plan";
    private static final String NODE_KIND_TASK    = "task";
    private static final String NODE_KIND_COMPOSE = "compose";
    private static final String PLAN_NODE_ID      = "assignment-plan";

    private static final Set<VerlaAgentEventType> SUPPORTED = EnumSet.of(
            VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
            VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_DETAILED);

    /** task_agent / task_name 截断上限：对应 DB 列已扩至 TEXT，此处保留软上限防止异常大值 */
    private static final int MAX_TASK_AGENT_LEN = 2000;
    private static final int MAX_TASK_NAME_LEN  = 512;

    private final VerlaWorkforceTaskRepository taskRepository;
    private final VerlaWorkforceTaskOutputRepository taskOutputRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Set<VerlaAgentEventType> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public void handle(VerlaEventInbox row, VerlaEventEnvelope env) {
        VerlaAgentEventType eventType;
        try {
            eventType = VerlaAgentEventType.valueOf(env.getEventType());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("[Verla/workforce] unknown eventType={}", env.getEventType());
            return;
        }
        if (eventType == VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED) {
            handleNodeUpdated(row, env);
        } else if (eventType == VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_DETAILED) {
            handleNodeDetailed(row, env);
        }
    }

    // -------------------------------------------------------------------------
    // ASSIGNMENT_AGENT_NODE_UPDATED
    // -------------------------------------------------------------------------

    private void handleNodeUpdated(VerlaEventInbox row, VerlaEventEnvelope env) {
        VerlaWorkforceNodeUpdatedPayload p = parsePayload(env, VerlaWorkforceNodeUpdatedPayload.class);
        if (p == null || p.getNode() == null) {
            log.warn("[Verla/workforce] NODE_UPDATED empty payload, sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }
        VerlaWorkforceNodeUpdatedPayload.Node node = p.getNode();
        if (node.getId() == null || node.getId().isBlank()) {
            log.warn("[Verla/workforce] NODE_UPDATED missing node.id, sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }

        // nodeType 字段是唯一分类依据；plan 节点额外保留 id 兜底以兼容旧 Python 版本
        boolean isPlan    = "plan".equalsIgnoreCase(node.getNodeType())
                || PLAN_NODE_ID.equals(node.getId());
        boolean isTask    = !isPlan && "task".equalsIgnoreCase(node.getNodeType());
        boolean isCompose = !isPlan && !isTask && "compose".equalsIgnoreCase(node.getNodeType());
        if (!isPlan && !isTask && !isCompose) {
            log.debug("[Verla/workforce] NODE_UPDATED skip unknown node sessionId={} nodeId={} nodeType={}",
                    row.getSessionId(), node.getId(), node.getNodeType());
            return;
        }
        VerlaWorkforceTask patch = buildTaskPatch(row, node, isPlan, isTask, isCompose);

        VerlaWorkforceTask saved = taskRepository.upsertBySessionNode(patch);
        log.info("[Verla/workforce] NODE_UPDATED upsert ok sessionId={} nodeId={} status={}",
                saved.getSessionId(), saved.getNodeId(), saved.getStatus());
    }

    private VerlaWorkforceTask buildTaskPatch(VerlaEventInbox row,
                                              VerlaWorkforceNodeUpdatedPayload.Node node,
                                              boolean isPlan,
                                              boolean isTask,
                                              boolean isCompose) {
        String nodeKind = isPlan ? NODE_KIND_PLAN : (isCompose ? NODE_KIND_COMPOSE : NODE_KIND_TASK);
        VerlaWorkforceTask.VerlaWorkforceTaskBuilder builder = VerlaWorkforceTask.builder()
                .conversationId(row.getConversationId())
                .turnId(row.getTurnId())
                .sessionId(row.getSessionId())
                .nodeId(node.getId())
                .nodeKind(nodeKind)
                .taskName(truncate(node.getTaskName(), MAX_TASK_NAME_LEN))
                .taskAgent(truncate(node.getTaskAgent(), MAX_TASK_AGENT_LEN))
                .status(node.getStatus())
                .content(node.getContent());

        if (isTask) {
            // id 格式为 task-{camelTaskId}，去除前缀还原原始 task id
            String rawId = node.getId() == null ? "" : node.getId();
            String camelTaskId = rawId.startsWith("task-")
                    ? rawId.substring("task-".length()) : (rawId.isBlank() ? null : rawId);
            builder.camelTaskId(camelTaskId);

            if (node.getProcessingTimeSeconds() != null) {
                builder.processingTimeMs((int) (node.getProcessingTimeSeconds() * 1000));
            }
        } else if (isCompose) {
            // compose 节点：持久化当前轮次和总轮次
            builder.composeCurrentRound(node.getComposeCurrentRound());
            builder.composeTotalRounds(node.getComposeTotalRounds());
        } else {
            // plan 节点：持久化 steps 数组和 compose 总轮次
            // 优先读显式 composeTotalRounds 字段；兜底解析 compose-part-* steps
            Integer composeTotalRounds = node.getComposeTotalRounds();
            List<?> steps = node.getSteps();
            if (steps != null && !steps.isEmpty()) {
                builder.planStepsJson(toJson(steps));
                if (composeTotalRounds != null && composeTotalRounds > 0) {
                    builder.planTaskCount(composeTotalRounds);
                } else if (containsComposePartSteps(steps)) {
                    builder.planTaskCount(steps.size());
                }
            } else if (composeTotalRounds != null && composeTotalRounds > 0) {
                builder.planTaskCount(composeTotalRounds);
            }
            builder.composeTotalRounds(composeTotalRounds);
        }

        return builder.build();
    }

    // -------------------------------------------------------------------------
    // ASSIGNMENT_AGENT_NODE_DETAILED
    // -------------------------------------------------------------------------

    private void handleNodeDetailed(VerlaEventInbox row, VerlaEventEnvelope env) {
        VerlaWorkforceNodeDetailedPayload p = parsePayload(env, VerlaWorkforceNodeDetailedPayload.class);
        if (p == null || p.getId() == null || p.getId().isBlank()) {
            log.warn("[Verla/workforce] NODE_DETAILED empty/missing id, sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }

        String detailJson = null;
        if (p.getDetailChunk() != null && !p.getDetailChunk().isEmpty()) {
            detailJson = toJson(p.getDetailChunk());
        }

        VerlaWorkforceTaskOutput patch = VerlaWorkforceTaskOutput.builder()
                .conversationId(row.getConversationId())
                .turnId(row.getTurnId())
                .sessionId(row.getSessionId())
                .nodeId(p.getId())
                .resultText(p.getContentChunk())
                .detailItemsJson(detailJson)
                .reset(Boolean.TRUE.equals(p.getReset()))
                .build();

        VerlaWorkforceTaskOutput saved = taskOutputRepository.upsertBySessionNode(patch);
        log.info("[Verla/workforce] NODE_DETAILED upsert ok sessionId={} nodeId={}",
                saved.getSessionId(), saved.getNodeId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private <T> T parsePayload(VerlaEventEnvelope env, Class<T> clazz) {
        if (env == null || env.getPayload() == null) return null;
        try {
            return objectMapper.convertValue(env.getPayload(), clazz);
        } catch (Exception e) {
            log.warn("[Verla/workforce] payload convert failed for {}: {}", clazz.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[Verla/workforce] JSON serialize failed: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        log.warn("[Verla/workforce] field truncated from {} to {} chars", s.length(), maxLen);
        return s.substring(0, maxLen);
    }

    /**
     * Compose 总轮 M 仅来自 {@code emit_compose_total} 写入的 {@code compose-part-*} steps，
     * 避免 decomposition / canvas 占位 steps 污染 {@code plan_task_count}。
     */
    private boolean containsComposePartSteps(List<?> steps) {
        for (Object step : steps) {
            if (!(step instanceof java.util.Map<?, ?> map)) {
                continue;
            }
            Object id = map.get("id");
            if (id != null && String.valueOf(id).startsWith("compose-part-")) {
                return true;
            }
        }
        return false;
    }
}
