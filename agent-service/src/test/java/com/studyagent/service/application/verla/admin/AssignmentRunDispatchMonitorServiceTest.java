package com.studyagent.service.application.verla.admin;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentRunDispatchMonitorServiceTest {

    @Mock
    private AssignmentRunDispatchMonitorRepository monitorRepository;
    @Mock
    private AssignmentRunDispatchGate assignmentRunDispatchGate;
    @Mock
    private MqOutboxRepository mqOutboxRepository;
    @Mock
    private VerlaEventInboxRepository eventInboxRepository;

    private AssignmentRunDispatchMonitorService service;

    @BeforeEach
    void setUp() {
        service = new AssignmentRunDispatchMonitorService(
                monitorRepository,
                assignmentRunDispatchGate,
                mqOutboxRepository,
                eventInboxRepository,
                new ObjectMapper());
    }

    @Test
    void getMonitor_usesGlobalCountsInsteadOfRecentListWindow() {
        when(assignmentRunDispatchGate.activeCount()).thenReturn(8);
        when(assignmentRunDispatchGate.maxConcurrency()).thenReturn(8);
        when(assignmentRunDispatchGate.isEnabled()).thenReturn(true);
        when(monitorRepository.countQueuedAssignmentRunSessions()).thenReturn(2);
        when(monitorRepository.countPendingAssignmentRunOutbox()).thenReturn(2);
        when(monitorRepository.listRecentAssignmentRuns(50)).thenReturn(List.of(
                row(1L, "DISPATCHING", MqOutbox.STATUS_SENT),
                row(2L, "RUNNING", MqOutbox.STATUS_SENT),
                row(3L, "DISPATCHING", MqOutbox.STATUS_UNSENT)));
        when(monitorRepository.countTerminalAssignmentRunsSince(any(), any())).thenReturn(0);
        when(monitorRepository.countStartedAssignmentRunsSince(any())).thenReturn(0);
        when(eventInboxRepository.findLatestProcessedBySession(anyLong())).thenReturn(null);

        AssignmentRunDispatchMonitorView view = service.getMonitor(50);

        assertEquals(8, view.getSummary().getActiveCount());
        assertEquals(8, view.getSummary().getInFlightCount());
        assertEquals(2, view.getSummary().getQueuedCount());
        assertEquals(2, view.getSummary().getPendingDispatchCount());
    }

    @Test
    void getMonitor_dispatchingSessionWithStaleQueuedEvent_isDispatchingNotQueued() {
        when(assignmentRunDispatchGate.activeCount()).thenReturn(1);
        when(assignmentRunDispatchGate.maxConcurrency()).thenReturn(8);
        when(assignmentRunDispatchGate.isEnabled()).thenReturn(true);
        when(monitorRepository.countQueuedAssignmentRunSessions()).thenReturn(0);
        when(monitorRepository.countPendingAssignmentRunOutbox()).thenReturn(0);
        when(monitorRepository.listRecentAssignmentRuns(10)).thenReturn(
                List.of(row(99L, "DISPATCHING", MqOutbox.STATUS_SENT)));
        when(monitorRepository.countTerminalAssignmentRunsSince(any(), any())).thenReturn(0);
        when(monitorRepository.countStartedAssignmentRunsSince(any())).thenReturn(0);
        when(eventInboxRepository.findLatestProcessedBySession(99L)).thenReturn(
                queuedEvent(VerlaAgentEventType.ASSIGNMENT_RUN_DISPATCH_QUEUED.name()));

        AssignmentRunDispatchMonitorView view = service.getMonitor(10);

        assertEquals("dispatching", view.getTasks().get(0).getLifecycle());
    }

    private static AssignmentRunDispatchTaskQueryRow row(
            Long sessionId, String sessionStatus, int outboxStatus) {
        AssignmentRunDispatchTaskQueryRow row = new AssignmentRunDispatchTaskQueryRow();
        row.setSessionId(sessionId);
        row.setSessionStatus(sessionStatus);
        row.setOutboxStatus(outboxStatus);
        row.setOutboxId(sessionId);
        row.setOutboxAction("cmd.assignment.run");
        row.setSessionCreatedAt(LocalDateTime.now());
        return row;
    }

    private static VerlaEventInbox queuedEvent(String eventType) {
        VerlaEventInbox inbox = new VerlaEventInbox();
        inbox.setEventType(eventType);
        inbox.setProcessedAt(LocalDateTime.now());
        return inbox;
    }
}
