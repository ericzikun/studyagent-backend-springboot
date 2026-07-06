package com.studyagent.service.application.verla.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.service.application.verla.admin.dto.AssignmentRunDispatchMonitorView;
import com.studyagent.service.application.verla.admin.dto.AssignmentRunDispatchTaskQueryRow;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.mq.MqOutboxRepository;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.dispatch.AssignmentRunDispatchGate;
import com.studyagent.service.domain.verla.repo.AssignmentRunDispatchMonitorRepository;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AssignmentRunDispatchMonitorService {

    private static final int DEFAULT_LIMIT = 50;

    private final AssignmentRunDispatchMonitorRepository monitorRepository;
    private final AssignmentRunDispatchGate assignmentRunDispatchGate;
    private final MqOutboxRepository mqOutboxRepository;
    private final VerlaEventInboxRepository eventInboxRepository;
    private final ObjectMapper objectMapper;

    public AssignmentRunDispatchMonitorView getMonitor(int limit) {
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 200);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since24h = now.minusHours(24);
        LocalDateTime since1h = now.minusHours(1);

        List<AssignmentRunDispatchTaskQueryRow> rows =
                monitorRepository.listRecentAssignmentRuns(safeLimit);

        int pendingDispatch = monitorRepository.countPendingAssignmentRunOutbox();
        int activeCount = assignmentRunDispatchGate.activeCount();
        int maxConcurrency = assignmentRunDispatchGate.maxConcurrency();

        List<AssignmentRunDispatchMonitorView.TaskRow> tasks = new ArrayList<>();
        for (AssignmentRunDispatchTaskQueryRow row : rows) {
            VerlaEventInbox latestEvent = eventInboxRepository.findLatestProcessedBySession(
                    row.getSessionId());
            tasks.add(toTaskRow(row, latestEvent));
        }

        int queuedCount = monitorRepository.countQueuedAssignmentRunSessions();

        AssignmentRunDispatchMonitorView.Summary summary =
                AssignmentRunDispatchMonitorView.Summary.builder()
                        .gateEnabled(assignmentRunDispatchGate.isEnabled())
                        .maxConcurrency(maxConcurrency)
                        .activeCount(activeCount)
                        .pendingDispatchCount(pendingDispatch)
                        .inFlightCount(activeCount)
                        .queuedCount(queuedCount)
                        .completedLast24Hours(monitorRepository.countTerminalAssignmentRunsSince(
                                "SUCCEEDED", since24h))
                        .failedLast24Hours(monitorRepository.countTerminalAssignmentRunsSince(
                                "FAILED", since24h))
                        .startedLastHour(monitorRepository.countStartedAssignmentRunsSince(since1h))
                        .completedLastHour(monitorRepository.countTerminalAssignmentRunsSince(
                                "SUCCEEDED", since1h))
                        .generatedAt(now)
                        .build();

        return AssignmentRunDispatchMonitorView.builder()
                .summary(summary)
                .tasks(tasks)
                .build();
    }

    private AssignmentRunDispatchMonitorView.TaskRow toTaskRow(
            AssignmentRunDispatchTaskQueryRow row,
            VerlaEventInbox latestEvent) {
        String lastEventType = latestEvent == null ? null : latestEvent.getEventType();
        LocalDateTime lastEventAt = latestEvent == null
                ? null
                : (latestEvent.getProcessedAt() != null
                ? latestEvent.getProcessedAt()
                : latestEvent.getReceivedAt());

        Integer queuePosition = null;
        if (row.getOutboxStatus() != null
                && row.getOutboxStatus() == MqOutbox.STATUS_UNSENT
                && row.getOutboxId() != null) {
            LocalDateTime createdAt = row.getOutboxCreatedAt() == null
                    ? row.getSessionCreatedAt() : row.getOutboxCreatedAt();
            queuePosition = mqOutboxRepository.countDeferredAssignmentRunAhead(
                    row.getOutboxId(), createdAt);
        } else if (VerlaAgentEventType.ASSIGNMENT_RUN_DISPATCH_QUEUED.name().equals(lastEventType)) {
            queuePosition = extractQueuePosition(latestEvent);
        }

        String lifecycle = resolveLifecycle(row.getSessionStatus(), row.getOutboxStatus(), lastEventType);
        String stageLabel = resolveStageLabel(lifecycle, lastEventType, queuePosition);

        LocalDateTime startedAt = row.getStartedAt() != null ? row.getStartedAt() : row.getSessionCreatedAt();
        Long durationSeconds = null;
        if (startedAt != null) {
            LocalDateTime end = row.getEndedAt() != null ? row.getEndedAt() : LocalDateTime.now();
            durationSeconds = Math.max(0, Duration.between(startedAt, end).getSeconds());
        }

        return AssignmentRunDispatchMonitorView.TaskRow.builder()
                .sessionId(row.getSessionId())
                .conversationId(row.getConversationId())
                .turnId(row.getTurnId())
                .outboxId(row.getOutboxId())
                .clerkUserId(row.getClerkUserId())
                .featureCode(row.getFeatureCode())
                .sessionStatus(row.getSessionStatus())
                .outboxStatus(outboxStatusLabel(row.getOutboxStatus()))
                .outboxAction(row.getOutboxAction())
                .lifecycle(lifecycle)
                .stageLabel(stageLabel)
                .lastEventType(lastEventType)
                .lastEventAt(lastEventAt)
                .queuePosition(queuePosition)
                .startedAt(startedAt)
                .endedAt(row.getEndedAt())
                .durationSeconds(durationSeconds)
                .outboxCreatedAt(row.getOutboxCreatedAt())
                .build();
    }

    private static String resolveLifecycle(String sessionStatus, Integer outboxStatus, String lastEventType) {
        if ("SUCCEEDED".equals(sessionStatus)) {
            return "completed";
        }
        if ("FAILED".equals(sessionStatus)) {
            return "failed";
        }
        if ("CANCELLED".equals(sessionStatus) || "CANCELLING".equals(sessionStatus)) {
            return "cancelled";
        }
        if (outboxStatus != null && outboxStatus == MqOutbox.STATUS_UNSENT) {
            return "queued";
        }
        if ("RUNNING".equals(sessionStatus)) {
            return "running";
        }
        if ("DISPATCHING".equals(sessionStatus)) {
            return "dispatching";
        }
        if (VerlaAgentEventType.ASSIGNMENT_RUN_DISPATCH_QUEUED.name().equals(lastEventType)) {
            return "queued";
        }
        return "dispatching";
    }

    private static String resolveStageLabel(String lifecycle, String lastEventType, Integer queuePosition) {
        return switch (lifecycle) {
            case "queued" -> queuePosition != null && queuePosition > 0
                    ? "Java dispatch queue (" + queuePosition + " ahead)"
                    : "Java dispatch queue (waiting for slot)";
            case "dispatching" -> "Dispatched to MQ / awaiting Py start";
            case "running" -> stageFromEvent(lastEventType);
            case "completed" -> "Completed";
            case "failed" -> "Failed";
            case "cancelled" -> "Cancelled";
            default -> lastEventType == null ? "Unknown" : lastEventType;
        };
    }

    private static String stageFromEvent(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "Running (no events yet)";
        }
        return switch (eventType) {
            case "ASSIGNMENT_AGENT_FLOW_STARTED" -> "Workforce started";
            case "ASSIGNMENT_AGENT_NODE_UPDATED", "ASSIGNMENT_WORKFLOW_NODE_UPDATED" ->
                    "Generating (workflow node update)";
            case "ASSIGNMENT_AGENT_NODE_DETAILED" -> "Generating (node detail streaming)";
            case "ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED", "ASSIGNMENT_ARTIFACT_UPDATED" ->
                    "Artifact updated";
            case "ASSIGNMENT_PROGRESS", "ASSIGNMENT_AGENT_FLOW_PROGRESS" -> "Progress update";
            default -> eventType;
        };
    }

    private static String outboxStatusLabel(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case MqOutbox.STATUS_UNSENT -> "UNSENT";
            case MqOutbox.STATUS_SENT -> "SENT";
            case MqOutbox.STATUS_FAILED -> "FAILED";
            case MqOutbox.STATUS_SENDING -> "SENDING";
            default -> String.valueOf(status);
        };
    }

    private Integer extractQueuePosition(VerlaEventInbox event) {
        if (event == null || event.getPayloadJson() == null) {
            return null;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(
                    event.getPayloadJson(), new TypeReference<Map<String, Object>>() {});
            Object payload = root.get("payload");
            if (!(payload instanceof Map<?, ?> map)) {
                return null;
            }
            Object value = map.get("queuePosition");
            if (value instanceof Number number) {
                return number.intValue();
            }
        } catch (Exception ignored) {
            // ignore malformed payload
        }
        return null;
    }
}
