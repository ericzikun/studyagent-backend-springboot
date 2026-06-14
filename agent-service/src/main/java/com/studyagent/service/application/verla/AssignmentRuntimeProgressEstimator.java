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
import com.studyagent.common.datetime.DateTimeFormats;
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

        if (countTaskNodesFromEvents(recentEvents).hasData()
                || workforce.hasTaskData()
                || hasComposeProgressData(workforce, recentEvents)) {
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
        // Plan 阶段映射到 20min 总轴的 warm-up（0→10%），与后续 workforce 阶段共用
        // computeSimulatedPercent，保证 plan → 子任务执行的衔接处倒计时单调不回弹。
        double completePercent = computeSimulatedPercent(flowStartedAt);
        int remainingSeconds = computeRemainingSeconds(completePercent);
        String label = resolvePlanPhaseLabel(agentNodes);
        return new AssignmentRuntimeProgressEstimate(
                label, remainingSeconds, completePercent, null, null, null, null);
    }

    private boolean isPlanOnlyPhase(List<VerlaEventInbox> recentEvents, WorkforceTaskProgressSnapshot workforce) {
        if (!isAssignmentGenerationActive(recentEvents)) {
            return false;
        }
        // 事件优先：事件折叠后已出现 task 节点即视为已离开 plan-only 阶段
        if (countTaskNodesFromEvents(recentEvents).hasData()) {
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

    private String resolvePlanPhaseLabel(List<Map<String, Object>> agentNodes) {
        if (agentNodes != null) {
            for (Map<String, Object> node : agentNodes) {
                if (node == null) {
                    continue;
                }
                if (!isPlanNode(node)) {
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

    /** 判断 foldAgentNodes 折叠后的节点是否为 plan 节点（优先 nodeType，兜底 id）。 */
    private boolean isPlanNode(Map<String, Object> node) {
        Object nodeType = node.get("nodeType");
        if (nodeType instanceof String t && !t.isBlank()) {
            return "plan".equalsIgnoreCase(t);
        }
        return "assignment-plan".equals(String.valueOf(node.get("id")));
    }

    /** 判断 foldAgentNodes 折叠后的节点是否为 compose 进度节点（优先 nodeType，兜底 id）。 */
    private boolean isComposeNode(Map<String, Object> node) {
        Object nodeType = node.get("nodeType");
        if (nodeType instanceof String t && !t.isBlank()) {
            return "compose".equalsIgnoreCase(t);
        }
        return "compose-progress".equals(String.valueOf(node.get("id")));
    }

    /** 判断 foldAgentNodes 折叠后的节点是否为子任务节点（优先 nodeType，兜底 id 前缀 task-）。 */
    private boolean isTaskNode(Map<String, Object> node) {
        Object nodeType = node.get("nodeType");
        if (nodeType instanceof String t && !t.isBlank()) {
            return "task".equalsIgnoreCase(t);
        }
        return String.valueOf(node.get("id")).startsWith("task-");
    }

    /**
     * 从折叠后的事件节点直接统计子任务进度（事件优先来源）。
     * total = task 节点数；completed/running 按归一化 status 计数。
     */
    private TaskNodeCounts countTaskNodesFromEvents(List<VerlaEventInbox> recentEvents) {
        int total = 0;
        int completed = 0;
        int running = 0;
        for (Map<String, Object> node : foldAgentNodes(recentEvents)) {
            if (node == null || !isTaskNode(node)) {
                continue;
            }
            total++;
            String status = normalizeNodeStatus(node.get("status"));
            if ("completed".equals(status)) {
                completed++;
            } else if ("running".equals(status)) {
                running++;
            }
        }
        return new TaskNodeCounts(total, completed, running);
    }

    private record TaskNodeCounts(int total, int completed, int running) {
        boolean hasData() {
            return total > 0;
        }
    }

    AssignmentRuntimeProgressEstimate estimateFromWorkforceSnapshot(
            WorkforceTaskProgressSnapshot workforce,
            List<VerlaEventInbox> recentEvents,
            LocalDateTime flowStartedAt,
            Long sessionId) {
        if (workforce == null) {
            return null;
        }

        // 子任务计数：事件折叠优先，DB 聚合兜底（事件窗口内无 task 节点时回退）
        TaskNodeCounts eventCounts = countTaskNodesFromEvents(recentEvents);
        int total = eventCounts.hasData() ? eventCounts.total() : workforce.totalTaskCount();
        int completed = eventCounts.hasData() ? eventCounts.completed() : workforce.completedTaskCount();
        int running = eventCounts.hasData() ? eventCounts.running() : workforce.activeTaskCount();

        // compose 轮次：事件优先（resolveComposeTotalRounds 内部已是事件>标题>DB 顺序），DB 兜底
        int composeTotal = resolveComposeTotalRounds(recentEvents, workforce.composeTotalRounds());
        int eventCurrentRound = resolveComposeCurrentRound(recentEvents, composeTotal);
        Integer composeCurrentRound;
        if (eventCurrentRound > 0) {
            composeCurrentRound = eventCurrentRound;
        } else {
            composeCurrentRound = workforce.composeCurrentRound();
        }
        Integer effectiveComposeTotalRounds = composeTotal > 0 ? composeTotal : null;
        double percent;

        if (total <= 0) {
            percent = computeSimulatedPercent(flowStartedAt);
        } else if (completed < total || running > 0) {
            double weighted = completed + (running > 0 ? RUNNING_NODE_PARTIAL_WEIGHT : 0.0);
            percent = Math.min(WORKFORCE_PHASE_WEIGHT_PERCENT, (weighted / total) * WORKFORCE_PHASE_WEIGHT_PERCENT);
        } else {
            if (composeTotal > 0) {
                int currentRound = composeCurrentRound == null
                        ? 0
                        : Math.min(composeCurrentRound, composeTotal);
                percent = WORKFORCE_PHASE_WEIGHT_PERCENT
                        + ((double) currentRound / composeTotal) * WORKFORCE_PHASE_WEIGHT_PERCENT;
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
        // 优先：compose-progress 节点携带的显式 composeTotalRounds 字段
        int fromExplicitField = 0;
        int parsedFromTitle = 0;
        for (Map<String, Object> node : foldAgentNodes(recentEvents)) {
            if (isComposeNode(node) || isPlanNode(node)) {
                Object raw = node.get("composeTotalRounds");
                if (raw instanceof Number n && n.intValue() > 0) {
                    fromExplicitField = Math.max(fromExplicitField, n.intValue());
                }
            }
            // 兜底：从标题 regex 解析（旧版 Python 兼容）
            Integer parsed = parseComposePartTitle(node).map(ComposePartProgress::total).orElse(null);
            if (parsed != null && parsed > 0) {
                parsedFromTitle = Math.max(parsedFromTitle, parsed);
            }
        }
        if (fromExplicitField > 0) {
            return fromExplicitField;
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
            // 优先：显式 composeCurrentRound 字段。
            // 注意：compose 完成时 Python 会把同一 node id 的 nodeType 从 "compose" 翻成
            // "task"（completed 事件不再带 composeCurrentRound），但 foldAgentNodes 按 id 合并、
            // putAll 不会清掉旧 key，合并后的节点仍残留最后一轮的 composeCurrentRound。这里不再
            // 用 isComposeNode 作为读取闸门，只要字段在就读——否则完成瞬间当前轮丢成 0、进度回落
            // 到 50% 相位地板，剩余时间会从接近 0 跳回 10min。
            Object raw = node.get("composeCurrentRound");
            if (raw instanceof Number n && n.intValue() > 0) {
                maxCurrent = Math.max(maxCurrent, Math.min(n.intValue(), composeTotalRounds));
            }
            // 兜底：从标题 regex 解析（旧版 Python 兼容）
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
                // nodeKind=="task" 已由 handler 保证，无需再做 id 前缀检查
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
        long startEpoch = DateTimeFormats.toEpochSecond(flowStartedAt);
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
