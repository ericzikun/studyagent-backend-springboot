package com.studyagent.service.application.verla;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.dto.AssignmentRuntimeProgressEstimate;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes V2 Assignment generation ETA on the Java side when Python does not emit one.
 * <p>
 * Mirrors the V1 detail/list formula ({@code 20min × (1 - progress%)}), but derives
 * progress from folded {@code ASSIGNMENT_AGENT_NODE_UPDATED} workflow nodes plus an
 * early simulated floor while the run is warming up.
 */
@Service
@RequiredArgsConstructor
public class AssignmentRuntimeProgressEstimator {

    static final int TOTAL_ESTIMATED_SECONDS = 20 * 60;
    static final int SIMULATED_PROGRESS_WINDOW_SECONDS = 120;
    static final double SIMULATED_PROGRESS_MAX_PERCENT = 10.0;
    static final double RUNNING_NODE_PARTIAL_WEIGHT = 0.5;

    private static final Set<String> ASSIGNMENT_RUN_EVENT_TYPES = Set.of(
            VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED.name(),
            VerlaAgentEventType.ASSIGNMENT_STARTED.name(),
            VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED.name(),
            VerlaAgentEventType.ASSIGNMENT_WORKFLOW_NODE_UPDATED.name(),
            VerlaAgentEventType.ASSIGNMENT_PROGRESS.name(),
            VerlaAgentEventType.AGENT_PROGRESS.name(),
            VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED.name());

    private static final Set<String> ASSIGNMENT_RUN_TERMINAL_EVENT_TYPES = Set.of(
            VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_COMPLETED.name(),
            VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_FAILED.name(),
            VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_CANCELLED.name(),
            VerlaAgentEventType.ASSIGNMENT_COMPLETED.name(),
            VerlaAgentEventType.ASSIGNMENT_FAILED.name(),
            VerlaAgentEventType.ASSIGNMENT_CANCELLED.name(),
            VerlaAgentEventType.AGENT_COMPLETED.name(),
            VerlaAgentEventType.AGENT_FAILED.name(),
            VerlaAgentEventType.AGENT_CANCELLED.name());

    private final ObjectMapper objectMapper;
    private final VerlaEventInboxRepository eventInboxRepository;

    /**
     * Resolves the latest backend-owned progress map for snapshot recovery.
     */
    public Map<String, Object> resolveProgress(List<VerlaEventInbox> recentEvents) {
        if (recentEvents == null || recentEvents.isEmpty()) {
            return null;
        }

        Map<String, Object> terminal = resolveTerminalProgress(recentEvents);
        if (terminal != null) {
            return terminal;
        }

        Map<String, Object> explicit = resolveExplicitProgress(recentEvents);
        if (explicit != null && hasExplicitEta(explicit)) {
            return explicit;
        }

        if (!isAssignmentGenerationActive(recentEvents)) {
            return explicit;
        }

        AssignmentRuntimeProgressEstimate computed = estimateFromEvents(recentEvents);
        if (computed == null) {
            return explicit;
        }

        Map<String, Object> merged = explicit == null ? new LinkedHashMap<>() : new LinkedHashMap<>(explicit);
        merged.putIfAbsent("label", computed.label());
        merged.put("estimatedRemainingSeconds", computed.estimatedRemainingSeconds());
        return merged.isEmpty() ? null : merged;
    }

    /**
     * Enriches SSE payloads for assignment-run events when Python omitted ETA fields.
     */
    public Map<String, Object> enrichAssignmentRunPayload(
            String eventType,
            Map<String, Object> payload,
            Long conversationId,
            VerlaEventInbox currentEvent) {
        if (payload == null || conversationId == null || !shouldEnrichSse(eventType)) {
            return payload;
        }
        if (containsExplicitEta(payload)) {
            return payload;
        }

        List<VerlaEventInbox> recentEvents = mergeRecentEvents(conversationId, currentEvent);
        if (!isAssignmentGenerationActive(recentEvents)) {
            return payload;
        }

        AssignmentRuntimeProgressEstimate computed = estimateFromEvents(recentEvents);
        if (computed == null) {
            return payload;
        }

        Map<String, Object> enriched = new LinkedHashMap<>(payload);
        Map<String, Object> progress = asMap(enriched.get("progress"));
        if (progress == null) {
            progress = new LinkedHashMap<>();
        } else {
            progress = new LinkedHashMap<>(progress);
        }
        progress.putIfAbsent("label", computed.label());
        progress.put("estimatedRemainingSeconds", computed.estimatedRemainingSeconds());
        enriched.put("progress", progress);
        return enriched;
    }

    public List<Map<String, Object>> foldAgentNodes(List<VerlaEventInbox> recentEvents) {
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

    AssignmentRuntimeProgressEstimate estimateFromEvents(List<VerlaEventInbox> recentEvents) {
        List<Map<String, Object>> agentNodes = foldAgentNodes(recentEvents);
        LocalDateTime flowStartedAt = resolveFlowStartedAt(recentEvents);
        return estimateFromAgentNodes(agentNodes, flowStartedAt);
    }

    AssignmentRuntimeProgressEstimate estimateFromAgentNodes(
            List<Map<String, Object>> agentNodes,
            LocalDateTime flowStartedAt) {
        double nodePercent = computeNodeProgressPercent(agentNodes);
        double simulatedPercent = computeSimulatedPercent(flowStartedAt);
        double effectivePercent = Math.max(nodePercent, simulatedPercent);
        effectivePercent = Math.max(0.0, Math.min(100.0, effectivePercent));

        int remainingSeconds = computeRemainingSeconds(effectivePercent);
        String label = resolveRunningLabel(agentNodes);
        return new AssignmentRuntimeProgressEstimate(label, remainingSeconds, effectivePercent);
    }

    private List<VerlaEventInbox> mergeRecentEvents(Long conversationId, VerlaEventInbox currentEvent) {
        List<VerlaEventInbox> recentEvents =
                eventInboxRepository.findRecentProcessedByConversation(conversationId, 300);
        if (currentEvent == null) {
            return recentEvents;
        }
        if (recentEvents.stream().anyMatch(event -> event.getId() != null && event.getId().equals(currentEvent.getId()))) {
            return recentEvents;
        }
        List<VerlaEventInbox> merged = new ArrayList<>(recentEvents);
        merged.add(currentEvent);
        merged.sort((a, b) -> Long.compare(nullToZero(b.getId()), nullToZero(a.getId())));
        return merged;
    }

    private Map<String, Object> resolveExplicitProgress(List<VerlaEventInbox> recentEvents) {
        for (VerlaEventInbox event : recentEvents) {
            Map<String, Object> progress = normalizeProgressPayload(sanitizedPayload(event));
            if (progress != null) {
                return progress;
            }
        }
        return null;
    }

    private Map<String, Object> resolveTerminalProgress(List<VerlaEventInbox> recentEvents) {
        for (VerlaEventInbox event : recentEvents) {
            VerlaAgentEventType type = parseEventType(event.getEventType());
            if (type == null) {
                continue;
            }
            Map<String, Object> terminal = terminalProgress(type);
            if (terminal != null) {
                return terminal;
            }
        }
        return null;
    }

    private boolean isAssignmentGenerationActive(List<VerlaEventInbox> recentEvents) {
        boolean sawRunMarker = false;
        for (VerlaEventInbox event : recentEvents) {
            String type = event.getEventType();
            if (type == null) {
                continue;
            }
            if (ASSIGNMENT_RUN_TERMINAL_EVENT_TYPES.contains(type)) {
                return false;
            }
            if (ASSIGNMENT_RUN_EVENT_TYPES.contains(type)) {
                sawRunMarker = true;
            }
        }
        return sawRunMarker;
    }

    private boolean shouldEnrichSse(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return false;
        }
        return ASSIGNMENT_RUN_EVENT_TYPES.contains(eventType);
    }

    private double computeNodeProgressPercent(List<Map<String, Object>> agentNodes) {
        if (agentNodes == null || agentNodes.isEmpty()) {
            return 0.0;
        }

        double weightedCompleted = 0.0;
        int countedNodes = 0;
        for (Map<String, Object> node : agentNodes) {
            if (node == null || node.isEmpty()) {
                continue;
            }
            countedNodes++;
            String status = normalizeNodeStatus(node.get("status"));
            if ("completed".equals(status)) {
                weightedCompleted += 1.0;
            } else if ("running".equals(status)) {
                weightedCompleted += RUNNING_NODE_PARTIAL_WEIGHT;
            }
        }
        if (countedNodes <= 0) {
            return 0.0;
        }
        return (weightedCompleted / countedNodes) * 100.0;
    }

    private double computeSimulatedPercent(LocalDateTime flowStartedAt) {
        if (flowStartedAt == null) {
            return 0.0;
        }
        long nowEpoch = System.currentTimeMillis() / 1000;
        long startEpoch = flowStartedAt.atZone(ZoneId.systemDefault()).toEpochSecond();
        long elapsedSeconds = Math.max(0, nowEpoch - startEpoch);
        long effectiveElapsed = Math.min(elapsedSeconds, SIMULATED_PROGRESS_WINDOW_SECONDS);
        double simulatedPercent =
                (effectiveElapsed * SIMULATED_PROGRESS_MAX_PERCENT) / SIMULATED_PROGRESS_WINDOW_SECONDS;
        return Math.min(simulatedPercent, SIMULATED_PROGRESS_MAX_PERCENT);
    }

    private int computeRemainingSeconds(double effectivePercent) {
        return Math.max(0, (int) Math.round(TOTAL_ESTIMATED_SECONDS * (1.0 - effectivePercent / 100.0)));
    }

    private String resolveRunningLabel(List<Map<String, Object>> agentNodes) {
        if (agentNodes != null) {
            for (Map<String, Object> node : agentNodes) {
                if (node == null) {
                    continue;
                }
                if (!"running".equals(normalizeNodeStatus(node.get("status")))) {
                    continue;
                }
                Object label = firstPresent(node, "title", "taskName", "summary", "subtitle");
                if (label instanceof String text && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return "Generating assignment";
    }

    private LocalDateTime resolveFlowStartedAt(List<VerlaEventInbox> recentEvents) {
        List<VerlaEventInbox> chronological = new ArrayList<>(recentEvents);
        Collections.reverse(chronological);
        for (VerlaEventInbox event : chronological) {
            if (VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED.name().equals(event.getEventType())
                    || VerlaAgentEventType.ASSIGNMENT_STARTED.name().equals(event.getEventType())) {
                if (event.getReceivedAt() != null) {
                    return event.getReceivedAt();
                }
                if (event.getProcessedAt() != null) {
                    return event.getProcessedAt();
                }
            }
        }
        return null;
    }

    private Map<String, Object> terminalProgress(VerlaAgentEventType type) {
        return switch (type) {
            case ASSIGNMENT_AGENT_FLOW_COMPLETED, ASSIGNMENT_COMPLETED, AGENT_COMPLETED ->
                    Map.of("label", "Assignment ready", "estimatedRemainingSeconds", 0);
            case ASSIGNMENT_AGENT_FLOW_FAILED, ASSIGNMENT_AGENT_FLOW_CANCELLED,
                    ASSIGNMENT_FAILED, ASSIGNMENT_CANCELLED, AGENT_FAILED, AGENT_CANCELLED -> {
                Map<String, Object> progress = new LinkedHashMap<>();
                progress.put("estimatedRemainingSeconds", null);
                yield progress;
            }
            default -> null;
        };
    }

    private Map<String, Object> normalizeProgressPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }

        Map<String, Object> progressSource = asMap(payload.get("progress"));
        boolean hasProgressObject = progressSource != null;
        if (progressSource == null) {
            progressSource = payload;
        }

        boolean hasEtaKey = containsAny(progressSource,
                "estimatedRemainingSeconds",
                "estRemainingTimeSeconds",
                "estimated_remaining_seconds",
                "est_remaining_time",
                "estimatedTimeRemainingSeconds");
        if (!hasProgressObject && !hasEtaKey && !progressSource.containsKey("label")) {
            return null;
        }

        Map<String, Object> progress = new LinkedHashMap<>();
        Object label = firstPresent(progressSource, "label");
        if (label instanceof String text && !text.isBlank()) {
            progress.put("label", text);
        }

        Object eta = firstPresent(progressSource,
                "estimatedRemainingSeconds",
                "estRemainingTimeSeconds",
                "estimated_remaining_seconds",
                "est_remaining_time",
                "estimatedTimeRemainingSeconds");
        Integer seconds = normalizeSeconds(eta);
        if (seconds != null) {
            progress.put("estimatedRemainingSeconds", seconds);
        } else if (eta == null && hasEtaKey) {
            progress.put("estimatedRemainingSeconds", null);
        }

        return progress.isEmpty() ? null : progress;
    }

    private boolean hasExplicitEta(Map<String, Object> progress) {
        return progress != null && progress.containsKey("estimatedRemainingSeconds")
                && progress.get("estimatedRemainingSeconds") instanceof Number;
    }

    private boolean containsExplicitEta(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        Map<String, Object> progress = asMap(payload.get("progress"));
        if (progress != null && hasExplicitEta(progress)) {
            return true;
        }
        return hasExplicitEta(payload);
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

    private String normalizeNodeStatus(Object raw) {
        if (raw == null) {
            return "queued";
        }
        String status = String.valueOf(raw).trim().toLowerCase();
        return switch (status) {
            case "running", "in_progress", "progressing" -> "running";
            case "completed", "done", "succeeded", "success" -> "completed";
            case "failed", "error", "cancelled", "canceled" -> "failed";
            default -> "queued";
        };
    }

    private Object firstPresent(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                return source.get(key);
            }
        }
        return null;
    }

    private boolean containsAny(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private Integer normalizeSeconds(Object value) {
        if (value instanceof Number number) {
            double seconds = number.doubleValue();
            if (!Double.isFinite(seconds) || seconds < 0 || seconds > Integer.MAX_VALUE) {
                return null;
            }
            return (int) Math.round(seconds);
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                double seconds = Double.parseDouble(text.trim());
                if (!Double.isFinite(seconds) || seconds < 0 || seconds > Integer.MAX_VALUE) {
                    return null;
                }
                return (int) Math.round(seconds);
            } catch (NumberFormatException ignored) {
                return null;
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
            // Some tests store payload objects directly.
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
}
