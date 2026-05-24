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

    private static final String NODE_KIND_PLAN = "plan";
    private static final String NODE_KIND_TASK = "task";
    private static final String PLAN_NODE_ID = "assignment-plan";

    private static final Set<VerlaAgentEventType> SUPPORTED = EnumSet.of(
            VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
            VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_DETAILED);

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

        boolean isPlan = PLAN_NODE_ID.equals(node.getId());
        VerlaWorkforceTask patch = buildTaskPatch(row, node, isPlan);

        VerlaWorkforceTask saved = taskRepository.upsertBySessionNode(patch);
        log.info("[Verla/workforce] NODE_UPDATED upsert ok sessionId={} nodeId={} status={}",
                saved.getSessionId(), saved.getNodeId(), saved.getStatus());
    }

    private VerlaWorkforceTask buildTaskPatch(VerlaEventInbox row,
                                              VerlaWorkforceNodeUpdatedPayload.Node node,
                                              boolean isPlan) {
        VerlaWorkforceTask.VerlaWorkforceTaskBuilder builder = VerlaWorkforceTask.builder()
                .conversationId(row.getConversationId())
                .turnId(row.getTurnId())
                .sessionId(row.getSessionId())
                .nodeId(node.getId())
                .nodeKind(isPlan ? NODE_KIND_PLAN : NODE_KIND_TASK)
                .taskName(node.getTaskName())
                .taskAgent(node.getTaskAgent())
                .status(node.getStatus())
                .content(node.getContent());

        if (!isPlan) {
            // task-{camelTaskId}
            String camelTaskId = node.getId().startsWith("task-")
                    ? node.getId().substring("task-".length()) : null;
            builder.camelTaskId(camelTaskId);

            if (node.getProcessingTimeSeconds() != null) {
                builder.processingTimeMs((int) (node.getProcessingTimeSeconds() * 1000));
            }
        } else {
            // plan 节点：持久化 steps 数组和子任务数量
            List<?> steps = node.getSteps();
            if (steps != null && !steps.isEmpty()) {
                builder.planStepsJson(toJson(steps));
                builder.planTaskCount(steps.size());
            }
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
}
