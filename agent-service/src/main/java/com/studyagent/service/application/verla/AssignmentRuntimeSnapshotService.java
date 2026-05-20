package com.studyagent.service.application.verla;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.dto.AssignmentRuntimeSnapshotPayloadView;
import com.studyagent.service.application.verla.dto.AssignmentRuntimeSnapshotView;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Assignment runtime recovery snapshot from existing Verla tables.
 *
 * This service is intentionally read-only and does not persist frontend concepts
 * such as activeTurn, phase, phaseStatus, or progress. The frontend restores UI
 * state from one real backend event type plus current payload data.
 */
@Service
@RequiredArgsConstructor
public class AssignmentRuntimeSnapshotService {

    private static final int MESSAGE_LIMIT = 100;
    private static final int EVENT_SCAN_LIMIT = 300;

    private final VerlaMessageRepository messageRepository;
    private final VerlaArtifactRepository artifactRepository;
    private final VerlaEventInboxRepository eventInboxRepository;
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

        return AssignmentRuntimeSnapshotView.builder()
                .conversationId(conversationId)
                .resumeAfterEventId(resumeAfterEventId)
                .stateEventType(stateEvent == null ? null : stateEvent.stateEventType())
                .payload(AssignmentRuntimeSnapshotPayloadView.builder()
                        .messages(messages)
                        .stateEventPayload(stateEvent == null ? null : stateEvent.payload())
                        .agentNodes(resolveAgentNodes(recentEvents))
                        .artifacts(artifacts == null ? List.of() : artifacts)
                        .build())
                .build();
    }

    private List<VerlaMessage> chronologicalMessages(List<VerlaMessage> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<VerlaMessage> messages = new ArrayList<>(rows);
        messages.sort((a, b) -> Long.compare(nullToZero(a.getId()), nullToZero(b.getId())));
        return messages;
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
                return new ResolvedStateEvent(resolved, sanitizedPayload(event));
            }
        }
        return null;
    }

    private String mapStateEventType(VerlaAgentEventType type) {
        return switch (type) {
            case ASSIGNMENT_AGENT_FLOW_COMPLETED, ASSIGNMENT_AGENT_FLOW_FAILED,
                    ASSIGNMENT_AGENT_FLOW_CANCELLED -> type.name();
            case ASSIGNMENT_AGENT_FLOW_STARTED -> VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED.name();
            case ASSIGNMENT_CLARIFY_STARTED, ASSIGNMENT_CLARIFY_STREAM_CHUNK ->
                    VerlaAgentEventType.ASSIGNMENT_CLARIFY_STARTED.name();
            case ASSIGNMENT_CLARIFY_COMPLETED -> type.name();
            case ASSIGNMENT_CLARIFY_FAILED, ASSIGNMENT_CLARIFY_CANCELLED -> type.name();
            case ASSIGNMENT_CLARIFY_FORM_READY -> VerlaAgentEventType.ASSIGNMENT_CLARIFY_FORM_READY.name();
            case ASSIGNMENT_DEEP_UNDERSTANDING_STARTED,
                    ASSIGNMENT_DEEP_UNDERSTANDING_STREAM_CHUNK ->
                    VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_STARTED.name();
            case ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED,
                    ASSIGNMENT_DEEP_UNDERSTANDING_FAILED -> type.name();
            case ASSIGNMENT_INIT_STARTED, ASSIGNMENT_INIT_STREAM_CHUNK ->
                    VerlaAgentEventType.ASSIGNMENT_INIT_STARTED.name();
            case ASSIGNMENT_INIT_COMPLETED, ASSIGNMENT_INIT_FAILED -> type.name();
            default -> null;
        };
    }

    private List<Map<String, Object>> resolveAgentNodes(List<VerlaEventInbox> recentEvents) {
        if (recentEvents == null || recentEvents.isEmpty()) {
            return List.of();
        }
        List<VerlaEventInbox> chronological = new ArrayList<>(recentEvents);
        Collections.reverse(chronological);

        Map<String, Map<String, Object>> nodesById = new LinkedHashMap<>();
        for (VerlaEventInbox event : chronological) {
            if (!isAgentNodeEvent(event.getEventType())) {
                continue;
            }
            Map<String, Object> payload = sanitizedPayload(event);
            Map<String, Object> node = asMap(payload.get("node"));
            if (node == null) {
                node = payload;
            }
            Map<String, Object> normalized = new LinkedHashMap<>(node);
            String id = resolveNodeId(normalized, event);
            if (id == null || id.isBlank()) {
                continue;
            }
            normalized.putIfAbsent("id", id);
            nodesById.merge(id, normalized, (previous, current) -> {
                Map<String, Object> merged = new LinkedHashMap<>(previous);
                merged.putAll(current);
                return merged;
            });
        }
        return List.copyOf(nodesById.values());
    }

    private boolean isAgentNodeEvent(String eventType) {
        return VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED.name().equals(eventType)
                || VerlaAgentEventType.ASSIGNMENT_WORKFLOW_NODE_UPDATED.name().equals(eventType);
    }

    private String resolveNodeId(Map<String, Object> node, VerlaEventInbox event) {
        Object raw = firstPresent(node, "id", "nodeId", "agentId", "key");
        if (raw != null && !String.valueOf(raw).isBlank()) {
            return String.valueOf(raw);
        }
        if (event.getStepId() != null && !event.getStepId().isBlank()) {
            return event.getStepId();
        }
        return event.getId() == null ? null : "node-" + event.getId();
    }

    private Object firstPresent(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                return source.get(key);
            }
        }
        return null;
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
