package com.studyagent.service.application.verla;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.dto.AssignmentRuntimeProgressEstimate;
import com.studyagent.service.domain.verla.WorkforceTaskProgressSnapshot;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaWorkforceTask;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import com.studyagent.service.domain.verla.repo.VerlaWorkforceTaskRepository;
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
 * Primary source: {@code verla_workforce_tasks} session aggregate (see
 * docs/V2/算法侧提供的耗时统计思路.md). Progress follows the V1 two-phase model:
 * workforce subtasks contribute the first 50%, compose rounds the second 50%.
 * Falls back to folded {@code ASSIGNMENT_AGENT_NODE_UPDATED} nodes when workforce
 * rows are not yet available.
 */
@Service
@RequiredArgsConstructor
public class AssignmentRuntimeProgressEstimator {

    static final int TOTAL_ESTIMATED_SECONDS = 20 * 60;
    /** Plan 阶段（子任务尚未拆解）单独用 2 分钟窗口平滑倒计时，直到 task-* 入库。 */
    static final int PLAN_PHASE_ESTIMATE_SECONDS = 120;
    static final int PLAN_PHASE_REMAINING_FLOOR_SECONDS = 15;
    static final int SIMULATED_PROGRESS_WINDOW_SECONDS = 120;
    static final double SIMULATED_PROGRESS_MAX_PERCENT = 10.0;
    static final double RUNNING_NODE_PARTIAL_WEIGHT = 0.5;
    static final double WORKFORCE_PHASE_WEIGHT_PERCENT = 50.0;

    private static final java.util.regex.Pattern COMPOSE_PART_TITLE =
            java.util.regex.Pattern.compile("Composing part (\\d+)/(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);

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
    private final VerlaWorkforceTaskRepository workforceTaskRepository;

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
        if (explicit != null && hasExplicitEta(explicit) && !isPlanOnlyPhase(recentEvents)) {
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
        merged.put("completePercent", roundPercent(computed.completePercent()));
        applyWorkforceMetadata(merged, computed);
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
        progress.put("completePercent", roundPercent(computed.completePercent()));
        applyWorkforceMetadata(progress, computed);
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
        LocalDateTime flowStartedAt = resolveFlowStartedAt(recentEvents);
        Long sessionId = resolveActiveSessionId(recentEvents);
        WorkforceTaskProgressSnapshot workforce = sessionId == null
                ? WorkforceTaskProgressSnapshot.empty()
                : workforceTaskRepository.aggregateProgressBySession(sessionId);

        if (workforce.hasTaskData() || hasComposeProgressData(workforce, recentEvents)) {
            AssignmentRuntimeProgressEstimate workforceEstimate = estimateFromWorkforceSnapshot(
                    workforce,
                    recentEvents,
                    flowStartedAt,
                    sessionId);
            if (workforceEstimate != null) {
                return workforceEstimate;
            }
        }

        if (isPlanOnlyPhase(recentEvents, workforce)) {
            return estimateFromPlanPhase(flowStartedAt, foldAgentNodes(recentEvents));
        }

        List<Map<String, Object>> agentNodes = foldAgentNodes(recentEvents);
        return estimateFromAgentNodes(agentNodes, flowStartedAt);
    }

    AssignmentRuntimeProgressEstimate estimateFromPlanPhase(
            LocalDateTime flowStartedAt,
            List<Map<String, Object>> agentNodes) {
        int remainingSeconds = computePlanPhaseRemainingSeconds(flowStartedAt);
        double planProgress = PLAN_PHASE_ESTIMATE_SECONDS <= 0
                ? 0.0
                : (1.0 - (double) remainingSeconds / PLAN_PHASE_ESTIMATE_SECONDS) * 100.0;
        planProgress = Math.max(0.0, Math.min(100.0, planProgress));
        // 映射到 20min 模型的完成度刻度（plan 窗口最多占 warm-up 10%）
        double completePercent = (planProgress / 100.0) * SIMULATED_PROGRESS_MAX_PERCENT;
        String label = resolvePlanPhaseLabel(agentNodes);
        return new AssignmentRuntimeProgressEstimate(
                label, remainingSeconds, completePercent, null, null, null, null);
    }

    private boolean isPlanOnlyPhase(List<VerlaEventInbox> recentEvents, WorkforceTaskProgressSnapshot workforce) {
        if (!isAssignmentGenerationActive(recentEvents)) {
            return false;
        }
        if (workforce != null && workforce.hasTaskData()) {
            return false;
        }
        return !hasComposeProgressData(workforce, recentEvents);
    }

    private boolean isPlanOnlyPhase(List<VerlaEventInbox> recentEvents) {
        Long sessionId = resolveActiveSessionId(recentEvents);
        WorkforceTaskProgressSnapshot workforce = sessionId == null
                ? WorkforceTaskProgressSnapshot.empty()
                : workforceTaskRepository.aggregateProgressBySession(sessionId);
        return isPlanOnlyPhase(recentEvents, workforce);
    }

    private boolean hasComposeProgressData(
            WorkforceTaskProgressSnapshot workforce,
            List<VerlaEventInbox> recentEvents) {
        if (workforce != null
                && workforce.composeTotalRounds() != null
                && workforce.composeTotalRounds() > 0) {
            return true;
        }
        return resolveComposeTotalRounds(recentEvents, null) > 0;
    }

    private int computePlanPhaseRemainingSeconds(LocalDateTime flowStartedAt) {
        if (flowStartedAt == null) {
            return PLAN_PHASE_ESTIMATE_SECONDS;
        }
        long nowEpoch = System.currentTimeMillis() / 1000;
        long startEpoch = flowStartedAt.atZone(ZoneId.systemDefault()).toEpochSecond();
        long elapsedSeconds = Math.max(0, nowEpoch - startEpoch);
        long remaining = PLAN_PHASE_ESTIMATE_SECONDS - elapsedSeconds;
        return (int) Math.max(PLAN_PHASE_REMAINING_FLOOR_SECONDS, remaining);
    }

    private String resolvePlanPhaseLabel(List<Map<String, Object>> agentNodes) {
        if (agentNodes != null) {
            for (Map<String, Object> node : agentNodes) {
                if (node == null) {
                    continue;
                }
                if (!"assignment-plan".equals(String.valueOf(node.get("id")))) {
                    continue;
                }
                Object label = firstPresent(node, "taskName", "title", "summary", "subtitle");
                if (label instanceof String text && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return "Make plan";
    }

    AssignmentRuntimeProgressEstimate estimateFromWorkforceSnapshot(
            WorkforceTaskProgressSnapshot workforce,
            List<VerlaEventInbox> recentEvents,
            LocalDateTime flowStartedAt,
            Long sessionId) {
        if (workforce == null) {
            return null;
        }

        int total = workforce.totalTaskCount();
        int completed = workforce.completedTaskCount();
        int running = workforce.activeTaskCount();
        Integer composeTotalRounds = workforce.composeTotalRounds();
        Integer composeCurrentRound = null;
        Integer effectiveComposeTotalRounds = composeTotalRounds;
        double percent;

        if (total <= 0) {
            percent = computeSimulatedPercent(flowStartedAt);
        } else if (completed < total || running > 0) {
            double weighted = completed + (running > 0 ? RUNNING_NODE_PARTIAL_WEIGHT : 0.0);
            percent = Math.min(WORKFORCE_PHASE_WEIGHT_PERCENT, (weighted / total) * WORKFORCE_PHASE_WEIGHT_PERCENT);
        } else {
            int composeTotal = resolveComposeTotalRounds(recentEvents, composeTotalRounds);
            composeCurrentRound = resolveComposeCurrentRound(recentEvents, composeTotal);
            if (composeTotal > 0) {
                effectiveComposeTotalRounds = composeTotal;
                percent = WORKFORCE_PHASE_WEIGHT_PERCENT
                        + ((double) composeCurrentRound / composeTotal) * WORKFORCE_PHASE_WEIGHT_PERCENT;
            } else {
                percent = WORKFORCE_PHASE_WEIGHT_PERCENT;
            }
        }

        percent = Math.max(computeSimulatedPercent(flowStartedAt), percent);
        percent = Math.max(0.0, Math.min(100.0, percent));

        int remainingSeconds = computeRemainingSeconds(percent);
        boolean inComposePhase = total > 0 && completed >= total && running == 0;
        String label = resolveWorkforceLabel(sessionId, recentEvents, running > 0, inComposePhase);
        return new AssignmentRuntimeProgressEstimate(
                label,
                remainingSeconds,
                percent,
                completed,
                total,
                composeCurrentRound,
                effectiveComposeTotalRounds);
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
        return new AssignmentRuntimeProgressEstimate(
                label, remainingSeconds, effectivePercent, null, null, null, null);
    }

    private Long resolveActiveSessionId(List<VerlaEventInbox> recentEvents) {
        if (recentEvents == null) {
            return null;
        }
        for (VerlaEventInbox event : recentEvents) {
            if (event == null || event.getSessionId() == null) {
                continue;
            }
            String type = event.getEventType();
            if (type != null && ASSIGNMENT_RUN_EVENT_TYPES.contains(type)) {
                return event.getSessionId();
            }
        }
        return null;
    }

    private int resolveComposeTotalRounds(
            List<VerlaEventInbox> recentEvents,
            Integer workforceComposeTotalRounds) {
        int parsedFromTitle = 0;
        for (Map<String, Object> node : foldAgentNodes(recentEvents)) {
            Integer parsed = parseComposePartTitle(node).map(ComposePartProgress::total).orElse(null);
            if (parsed != null && parsed > 0) {
                parsedFromTitle = Math.max(parsedFromTitle, parsed);
            }
        }
        if (parsedFromTitle > 0) {
            return parsedFromTitle;
        }
        if (workforceComposeTotalRounds != null && workforceComposeTotalRounds > 0) {
            return workforceComposeTotalRounds;
        }
        return 0;
    }

    private int resolveComposeCurrentRound(List<VerlaEventInbox> recentEvents, int composeTotalRounds) {
        if (composeTotalRounds <= 0) {
            return 0;
        }
        int maxCurrent = 0;
        for (Map<String, Object> node : foldAgentNodes(recentEvents)) {
            java.util.Optional<ComposePartProgress> parsed = parseComposePartTitle(node);
            if (parsed.isPresent()) {
                maxCurrent = Math.max(maxCurrent, Math.min(parsed.get().current(), composeTotalRounds));
            }
        }
        return maxCurrent;
    }

    private java.util.Optional<ComposePartProgress> parseComposePartTitle(Map<String, Object> node) {
        if (node == null) {
            return java.util.Optional.empty();
        }
        Object raw = firstPresent(node, "title", "taskName", "summary", "subtitle");
        if (!(raw instanceof String text) || text.isBlank()) {
            return java.util.Optional.empty();
        }
        java.util.regex.Matcher matcher = COMPOSE_PART_TITLE.matcher(text.trim());
        if (!matcher.find()) {
            return java.util.Optional.empty();
        }
        try {
            int current = Integer.parseInt(matcher.group(1));
            int total = Integer.parseInt(matcher.group(2));
            if (current <= 0 || total <= 0) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new ComposePartProgress(current, total));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }

    private String resolveWorkforceLabel(
            Long sessionId,
            List<VerlaEventInbox> recentEvents,
            boolean hasRunningTask,
            boolean inComposePhase) {
        if (sessionId != null && hasRunningTask) {
            for (VerlaWorkforceTask task : workforceTaskRepository.listBySession(sessionId)) {
                if (task == null || !"task".equalsIgnoreCase(task.getNodeKind())) {
                    continue;
                }
                if (task.getNodeId() == null || !task.getNodeId().startsWith("task-")) {
                    continue;
                }
                if ("running".equals(normalizeNodeStatus(task.getStatus()))
                        && task.getTaskName() != null
                        && !task.getTaskName().isBlank()) {
                    return task.getTaskName().trim();
                }
            }
        }
        if (inComposePhase) {
            for (Map<String, Object> node : foldAgentNodes(recentEvents)) {
                java.util.Optional<ComposePartProgress> parsed = parseComposePartTitle(node);
                if (parsed.isPresent()) {
                    return "Composing part " + parsed.get().current() + "/" + parsed.get().total();
                }
            }
            return "Composing assignment";
        }
        return resolveRunningLabel(foldAgentNodes(recentEvents));
    }

    private void applyWorkforceMetadata(Map<String, Object> progress, AssignmentRuntimeProgressEstimate computed) {
        if (progress == null || computed == null) {
            return;
        }
        if (computed.completedTaskCount() != null) {
            progress.put("completedTaskCount", computed.completedTaskCount());
        }
        if (computed.totalTaskCount() != null) {
            progress.put("totalTaskCount", computed.totalTaskCount());
        }
        if (computed.composeCurrentRound() != null) {
            progress.put("composeCurrentRound", computed.composeCurrentRound());
        }
        if (computed.composeTotalRounds() != null) {
            progress.put("composeTotalRounds", computed.composeTotalRounds());
        }
    }

    private double roundPercent(double percent) {
        return Math.round(percent * 10.0) / 10.0;
    }

    private record ComposePartProgress(int current, int total) {
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
        if (recentEvents == null || recentEvents.isEmpty()) {
            return null;
        }
        // §2.2 priority 2: only the latest event may passthrough explicit ETA.
        return normalizeProgressPayload(sanitizedPayload(recentEvents.get(0)));
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
