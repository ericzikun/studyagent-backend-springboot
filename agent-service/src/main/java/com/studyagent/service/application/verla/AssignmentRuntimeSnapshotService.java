package com.studyagent.service.application.verla;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.dto.AssignmentRuntimeSnapshotPayloadView;
import com.studyagent.service.application.verla.dto.AssignmentRuntimeSnapshotView;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.mq.MqOutboxRepository;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaArtifactEditProposal;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaWorkforceTask;
import com.studyagent.service.domain.verla.VerlaWorkforceTaskOutput;
import com.studyagent.service.domain.verla.repo.VerlaArtifactEditProposalRepository;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaWorkforceTaskOutputRepository;
import com.studyagent.service.domain.verla.repo.VerlaWorkforceTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Assignment runtime recovery snapshot from existing Verla tables.
 *
 * This service is intentionally read-only and does not persist frontend concepts
 * such as activeTurn, phase, or phaseStatus. Runtime progress is folded from
 * backend event payloads so refresh/reopen can recover the same ETA shown by SSE.
 */
@Service
@RequiredArgsConstructor
public class AssignmentRuntimeSnapshotService {

    private static final int MESSAGE_LIMIT = 100;
    private static final int EVENT_SCAN_LIMIT = 100;

    private static final List<String> GATED_ASSIGNMENT_RUN_ACTIONS = List.of(
            VerlaCommandAction.CMD_ASSIGNMENT_RUN.getCode(),
            VerlaCommandAction.CMD_AGENT_RETRY.getCode());

    private final VerlaMessageRepository messageRepository;
    private final VerlaArtifactRepository artifactRepository;
    private final VerlaEventInboxRepository eventInboxRepository;
    private final AssignmentRuntimeProgressEstimator progressEstimator;
    private final VerlaWorkforceTaskRepository taskRepository;
    private final VerlaWorkforceTaskOutputRepository taskOutputRepository;
    private final VerlaArtifactEditProposalRepository editProposalRepository;
    private final MqOutboxRepository mqOutboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Returns a single current-state snapshot for refresh/reopen recovery.
     */
    public AssignmentRuntimeSnapshotView getSnapshot(Long conversationId) {
        List<VerlaEventInbox> recentEvents =
                eventInboxRepository.findRecentProcessedByConversation(conversationId, EVENT_SCAN_LIMIT);
        Long resumeAfterEventId = recentEvents == null || recentEvents.isEmpty()
                ? 0L
                : recentEvents.get(0).getId();

        List<VerlaMessage> messages = chronologicalMessages(
                messageRepository.findByCursor(conversationId, null, MESSAGE_LIMIT));
        List<VerlaArtifact> artifacts = artifactRepository.findByConversation(conversationId);
        ResolvedStateEvent stateEvent = resolveStateEvent(recentEvents);
        List<Map<String, Object>> agentNodes = withPersistedNodeDetails(
                progressEstimator.foldAgentNodes(recentEvents),
                resolveCurrentSessionId(recentEvents));
        Map<String, Object> progress = resolveSnapshotProgress(recentEvents, stateEvent);

        return AssignmentRuntimeSnapshotView.builder()
                .conversationId(conversationId)
                .resumeAfterEventId(resumeAfterEventId)
                .stateEventType(stateEvent == null ? null : stateEvent.stateEventType())
                .payload(AssignmentRuntimeSnapshotPayloadView.builder()
                        .messages(messages)
                        .stateEventPayload(stateEvent == null ? null : stateEvent.payload())
                        .progress(progress)
                        .agentNodes(agentNodes)
                        .artifacts(artifacts == null ? List.of() : artifacts)
                        .artifactEditProposal(resolveActiveEditProposal(conversationId))
                        .build())
                .build();
    }

    /**
     * 当前 conversation 下最新一条活跃（GENERATING / REVIEWING）的 Edit Proposal，供刷新恢复
     * 蒙层 / 内联 diff（设计 §4.8）。无活跃提案返回 null。
     */
    private Map<String, Object> resolveActiveEditProposal(Long conversationId) {
        List<VerlaArtifactEditProposal> active = editProposalRepository.findActiveByConversation(conversationId);
        if (active == null || active.isEmpty()) {
            return null;
        }
        VerlaArtifactEditProposal p = active.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", VerlaArtifactEditProposal.STATE_GENERATING.equals(p.getState())
                ? "generating" : "reviewing");
        out.put("proposalId", p.getProposalId());
        out.put("targets", mergeTargetsWithChanges(p));
        return out;
    }

    /** targets_json（含 editMode/baseVersionNo）与 changes_json（review hunks）合并回 targets[].changes。 */
    private List<Map<String, Object>> mergeTargetsWithChanges(VerlaArtifactEditProposal p) {
        List<Map<String, Object>> targets = readJsonList(p.getTargetsJson());
        Map<String, Object> changesByUid = readJsonMap(p.getChangesJson());
        for (Map<String, Object> t : targets) {
            Object uid = t.get("artifactUid");
            if (uid != null && changesByUid.containsKey(String.valueOf(uid)) && t.get("changes") == null) {
                t.put("changes", changesByUid.get(String.valueOf(uid)));
            }
        }
        return targets;
    }

    private List<Map<String, Object>> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Restores detail panel state into the corresponding folded node without changing
     * live SSE semantics. Card `content` remains the card snapshot; `detailed.content`
     * is the accumulated ASSIGNMENT_AGENT_NODE_DETAILED output, while
     * `detailed.durationMs` comes from the task row's completed processing time.
     */
    private List<Map<String, Object>> withPersistedNodeDetails(
            List<Map<String, Object>> agentNodes,
            Long sessionId) {
        if (agentNodes == null || agentNodes.isEmpty() || sessionId == null) {
            return agentNodes == null ? List.of() : agentNodes;
        }
        List<VerlaWorkforceTaskOutput> outputs = taskOutputRepository.listBySession(sessionId);
        List<VerlaWorkforceTask> tasks = taskRepository.listBySession(sessionId);
        if ((outputs == null || outputs.isEmpty()) && (tasks == null || tasks.isEmpty())) {
            return agentNodes;
        }

        Map<String, VerlaWorkforceTaskOutput> outputByNodeId = new LinkedHashMap<>();
        if (outputs != null) {
            for (VerlaWorkforceTaskOutput output : outputs) {
                if (output.getNodeId() != null && !output.getNodeId().isBlank()) {
                    outputByNodeId.put(output.getNodeId(), output);
                }
            }
        }
        Map<String, VerlaWorkforceTask> taskByNodeId = new LinkedHashMap<>();
        if (tasks != null) {
            for (VerlaWorkforceTask task : tasks) {
                if (task.getNodeId() != null && !task.getNodeId().isBlank()) {
                    taskByNodeId.put(task.getNodeId(), task);
                }
            }
        }
        if (outputByNodeId.isEmpty() && taskByNodeId.isEmpty()) {
            return agentNodes;
        }

        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Map<String, Object> node : agentNodes) {
            String nodeId = node == null ? null : stringValue(node.get("id"));
            VerlaWorkforceTaskOutput output = nodeId == null ? null : outputByNodeId.get(nodeId);
            VerlaWorkforceTask task = nodeId == null ? null : taskByNodeId.get(nodeId);
            Integer durationMs = resolveDurationMs(task);
            if (!hasNodeDetail(output) && durationMs == null) {
                enriched.add(node);
                continue;
            }

            Map<String, Object> nextNode = new LinkedHashMap<>(node);
            Map<String, Object> detailed = new LinkedHashMap<>();
            if (output != null && output.getResultText() != null && !output.getResultText().isBlank()) {
                detailed.put("content", output.getResultText());
            }
            List<Map<String, Object>> detailItems = parseDetailItems(output == null ? null : output.getDetailItemsJson());
            if (!detailItems.isEmpty()) {
                detailed.put("detailItems", detailItems);
            }
            if (durationMs != null) {
                detailed.put("durationMs", durationMs);
            }
            nextNode.put("detailed", detailed);
            enriched.add(nextNode);
        }
        return enriched;
    }

    private Long resolveCurrentSessionId(List<VerlaEventInbox> recentEvents) {
        if (recentEvents == null || recentEvents.isEmpty()) {
            return null;
        }
        for (VerlaEventInbox event : recentEvents) {
            if (event.getSessionId() != null) {
                return event.getSessionId();
            }
        }
        return null;
    }

    private boolean hasNodeDetail(VerlaWorkforceTaskOutput output) {
        return output != null
                && ((output.getResultText() != null && !output.getResultText().isBlank())
                || (output.getDetailItemsJson() != null && !output.getDetailItemsJson().isBlank()));
    }

    private Integer resolveDurationMs(VerlaWorkforceTask task) {
        if (task == null || task.getProcessingTimeMs() == null || task.getProcessingTimeMs() < 0) {
            return null;
        }
        return task.getProcessingTimeMs();
    }

    private List<Map<String, Object>> parseDetailItems(String detailItemsJson) {
        if (detailItemsJson == null || detailItemsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    detailItemsJson,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<VerlaMessage> chronologicalMessages(List<VerlaMessage> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<VerlaMessage> messages = new ArrayList<>(rows);
        messages.sort((a, b) -> Long.compare(nullToZero(a.getId()), nullToZero(b.getId())));
        return messages;
    }

    private Map<String, Object> resolveSnapshotProgress(
            List<VerlaEventInbox> recentEvents,
            ResolvedStateEvent stateEvent) {
        if (stateEvent != null
                && VerlaAgentEventType.ASSIGNMENT_RUN_DISPATCHED.name().equals(stateEvent.stateEventType())) {
            return dispatchedProgress(stateEvent.payload());
        }
        return progressEstimator.resolveProgress(recentEvents);
    }

    private ResolvedStateEvent resolveStateEvent(List<VerlaEventInbox> recentEvents) {
        if (recentEvents == null || recentEvents.isEmpty()) {
            return null;
        }
        for (VerlaEventInbox event : recentEvents) {
            VerlaAgentEventType type = parseEventType(event.getEventType());
            if (type == null) {
                continue;
            }
            String resolved = mapStateEventType(type);
            if (resolved != null) {
                ResolvedStateEvent resolvedEvent =
                        new ResolvedStateEvent(resolved, sanitizedPayload(event));
                if (VerlaAgentEventType.ASSIGNMENT_RUN_DISPATCH_QUEUED.name().equals(resolved)) {
                    return correctStaleDispatchQueuedForEvent(event, resolvedEvent);
                }
                return resolvedEvent;
            }
        }
        return null;
    }

    private ResolvedStateEvent correctStaleDispatchQueuedForEvent(
            VerlaEventInbox event,
            ResolvedStateEvent stateEvent) {
        Long sessionId = event == null ? null : event.getSessionId();
        if (sessionId == null) {
            return stateEvent;
        }
        Integer outboxStatus = mqOutboxRepository.findLatestStatusBySessionIdAndActions(
                sessionId, GATED_ASSIGNMENT_RUN_ACTIONS);
        if (outboxStatus == null || outboxStatus == MqOutbox.STATUS_UNSENT) {
            return stateEvent;
        }
        return new ResolvedStateEvent(
                VerlaAgentEventType.ASSIGNMENT_RUN_DISPATCHED.name(),
                dispatchedProgress(stateEvent.payload()));
    }

    private static Map<String, Object> dispatchedProgress(Map<String, Object> source) {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("label", "Starting assignment workflow");
        progress.put("reason", "dispatch_gate_released");
        if (source != null) {
            Object maxConcurrency = source.get("maxConcurrency");
            if (maxConcurrency != null) {
                progress.put("maxConcurrency", maxConcurrency);
            }
            Object activeCount = source.get("activeCount");
            if (activeCount != null) {
                progress.put("activeCount", activeCount);
            }
        }
        return progress;
    }

    private String mapStateEventType(VerlaAgentEventType type) {
        return switch (type) {
            case ASSIGNMENT_AGENT_FLOW_COMPLETED, ASSIGNMENT_AGENT_FLOW_FAILED,
                    ASSIGNMENT_AGENT_FLOW_CANCELLED -> type.name();
            case ASSIGNMENT_AGENT_FLOW_STARTED -> VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED.name();
            case ASSIGNMENT_RUN_DISPATCHED, ASSIGNMENT_RUN_DISPATCH_QUEUED -> type.name();
            case ASSIGNMENT_CLARIFY_STARTED, ASSIGNMENT_CLARIFY_STREAM_CHUNK ->
                    VerlaAgentEventType.ASSIGNMENT_CLARIFY_STARTED.name();
            case ASSIGNMENT_CLARIFY_COMPLETED -> type.name();
            case ASSIGNMENT_CLARIFY_FAILED, ASSIGNMENT_CLARIFY_CANCELLED -> type.name();
            case ASSIGNMENT_CLARIFY_FORM_READY -> VerlaAgentEventType.ASSIGNMENT_CLARIFY_FORM_READY.name();
            case ASSIGNMENT_DEEP_UNDERSTANDING_STARTED,
                    ASSIGNMENT_DEEP_UNDERSTANDING_STREAM_CHUNK ->
                    VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_STARTED.name();
            case ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED,
                    ASSIGNMENT_DEEP_UNDERSTANDING_FAILED,
                    ASSIGNMENT_DEEP_UNDERSTANDING_CANCELLED -> type.name();
            case ASSIGNMENT_INIT_STARTED, ASSIGNMENT_INIT_STREAM_CHUNK ->
                    VerlaAgentEventType.ASSIGNMENT_INIT_STARTED.name();
            case ASSIGNMENT_INIT_COMPLETED, ASSIGNMENT_INIT_FAILED,
                    ASSIGNMENT_INIT_CANCELLED -> type.name();
            default -> null;
        };
    }

    private Map<String, Object> sanitizedPayload(VerlaEventInbox event) {
        Map<String, Object> payload = parsePayload(event.getPayloadJson());
        return VerlaFrontendPayloadSanitizer.sanitize(event.getEventType(), payload);
    }

    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            VerlaEventEnvelope envelope = objectMapper.readValue(payloadJson, VerlaEventEnvelope.class);
            if (envelope.getPayload() != null) {
                return envelope.getPayload();
            }
        } catch (Exception ignored) {
            // Some older tests and tools store the payload object directly.
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(
                    payloadJson, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> payload = asMap(raw.get("payload"));
            return payload == null ? raw : payload;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        return (Map<String, Object>) raw;
    }

    private VerlaAgentEventType parseEventType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return VerlaAgentEventType.valueOf(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private record ResolvedStateEvent(String stateEventType, Map<String, Object> payload) {
    }
}
