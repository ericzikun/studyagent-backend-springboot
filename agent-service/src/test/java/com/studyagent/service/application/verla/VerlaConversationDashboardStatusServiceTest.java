package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.service.domain.verla.VerlaClarifyForm;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaClarifyFormRepository;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import com.studyagent.service.domain.verla.state.IntentLifecycle;
import com.studyagent.service.domain.verla.state.SessionStatus;
import com.studyagent.service.domain.verla.state.TurnStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerlaConversationDashboardStatusServiceTest {

    private FakeTurnRepository turnRepository;
    private FakeSessionRepository sessionRepository;
    private FakeClarifyFormRepository clarifyFormRepository;
    private FakeEventInboxRepository eventInboxRepository;
    private VerlaConversationDashboardStatusService service;

    @BeforeEach
    void setUp() {
        turnRepository = new FakeTurnRepository();
        sessionRepository = new FakeSessionRepository();
        clarifyFormRepository = new FakeClarifyFormRepository();
        eventInboxRepository = new FakeEventInboxRepository();
        service = new VerlaConversationDashboardStatusService(
                turnRepository,
                sessionRepository,
                clarifyFormRepository,
                eventInboxRepository,
                new ObjectMapper());
    }

    @Test
    void resolve_returnsNeedsChoiceForUnconfirmedIntent() {
        VerlaConversation conversation = conversation(1L, null)
                .intentLifecycle(IntentLifecycle.AWAITING_USER_CONFIRMATION.getDbValue())
                .build();

        assertEquals(
                VerlaConversationDashboardStatusService.STATUS_NEEDS_CHOICE,
                service.resolve(conversation));
    }

    @Test
    void resolve_returnsNeedsChoiceForAwaitingClarifyTurn() {
        VerlaConversation conversation = conversation(2L, 20L).build();
        turnRepository.byId.put(20L, turn(20L, 2L, TurnStatus.AWAITING_CLARIFY, null));

        assertEquals(
                VerlaConversationDashboardStatusService.STATUS_NEEDS_CHOICE,
                service.resolve(conversation));
    }

    @Test
    void resolve_returnsProgressingForRunningSession() {
        VerlaConversation conversation = conversation(3L, 30L).build();
        turnRepository.byId.put(30L, turn(30L, 3L, TurnStatus.RUNNING_AGENT, 300L));
        sessionRepository.byId.put(300L, session(300L, 30L, SessionStatus.RUNNING));

        assertEquals(
                VerlaConversationDashboardStatusService.STATUS_PROGRESSING,
                service.resolve(conversation));
    }

    @Test
    void resolve_returnsCompletedForCompletedTurn() {
        VerlaConversation conversation = conversation(4L, 40L).build();
        turnRepository.byId.put(40L, turn(40L, 4L, TurnStatus.COMPLETED, 400L));
        sessionRepository.byId.put(400L, session(400L, 40L, SessionStatus.SUCCEEDED));

        assertEquals(
                VerlaConversationDashboardStatusService.STATUS_COMPLETED,
                service.resolve(conversation));
    }

    @Test
    void resolve_returnsFailedWhenActiveSessionFailed() {
        VerlaConversation conversation = conversation(5L, 50L).build();
        turnRepository.byId.put(50L, turn(50L, 5L, TurnStatus.RUNNING_AGENT, 500L));
        sessionRepository.byId.put(500L, session(500L, 50L, SessionStatus.FAILED));

        assertEquals(
                VerlaConversationDashboardStatusService.STATUS_FAILED,
                service.resolve(conversation));
    }

    @Test
    void resolve_returnsCompletedOnlyForFinalFlowCompletedEvent() {
        VerlaConversation conversation = conversation(8L, 80L).build();
        turnRepository.byId.put(80L, turn(80L, 8L, TurnStatus.RUNNING_AGENT, 800L));
        sessionRepository.byId.put(800L, session(800L, 80L, SessionStatus.RUNNING));
        eventInboxRepository.add(8L, event(
                1L,
                8L,
                VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_COMPLETED,
                "{}"));

        assertEquals(
                VerlaConversationDashboardStatusService.STATUS_COMPLETED,
                service.resolve(conversation));
    }

    @Test
    void resolve_doesNotTreatIntermediateCompletedEventAsFinalCompleted() {
        VerlaConversation conversation = conversation(9L, 90L).build();
        turnRepository.byId.put(90L, turn(90L, 9L, TurnStatus.RUNNING_AGENT, 900L));
        sessionRepository.byId.put(900L, session(900L, 90L, SessionStatus.SUCCEEDED));
        eventInboxRepository.add(9L, event(
                1L,
                9L,
                VerlaAgentEventType.ASSIGNMENT_INIT_COMPLETED,
                "{\"payload\":{\"ready\":false,\"isReadyForGeneration\":false}}"));

        assertEquals(
                VerlaConversationDashboardStatusService.STATUS_PROGRESSING,
                service.resolve(conversation));
    }

    @Test
    void resolve_doesNotTreatSucceededSessionWithoutFinalTurnOrEventAsCompleted() {
        VerlaConversation conversation = conversation(12L, 120L).build();
        turnRepository.byId.put(120L, turn(120L, 12L, TurnStatus.RUNNING_AGENT, 1200L));
        sessionRepository.byId.put(1200L, session(1200L, 120L, SessionStatus.SUCCEEDED));

        assertEquals(
                VerlaConversationDashboardStatusService.STATUS_PROGRESSING,
                service.resolve(conversation));
    }

    @Test
    void resolve_returnsNeedsChoiceForReadyIntermediateEvent() {
        VerlaConversation conversation = conversation(10L, 100L).build();
        turnRepository.byId.put(100L, turn(100L, 10L, TurnStatus.RUNNING_AGENT, 1000L));
        sessionRepository.byId.put(1000L, session(1000L, 100L, SessionStatus.SUCCEEDED));
        eventInboxRepository.add(10L, event(
                1L,
                10L,
                VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED,
                "{\"payload\":{\"ready\":true}}"));

        assertEquals(
                VerlaConversationDashboardStatusService.STATUS_NEEDS_CHOICE,
                service.resolve(conversation));
    }

    @Test
    void resolve_usesLatestEffectiveEventOverOlderClarifyFormReady() {
        VerlaConversation conversation = conversation(11L, 110L).build();
        turnRepository.byId.put(110L, turn(110L, 11L, TurnStatus.RUNNING_AGENT, 1100L));
        sessionRepository.byId.put(1100L, session(1100L, 110L, SessionStatus.RUNNING));
        eventInboxRepository.add(11L, event(
                1L,
                11L,
                VerlaAgentEventType.ASSIGNMENT_CLARIFY_FORM_READY,
                "{}"));
        eventInboxRepository.add(11L, event(
                2L,
                11L,
                VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                "{}"));

        assertEquals(
                VerlaConversationDashboardStatusService.STATUS_PROGRESSING,
                service.resolve(conversation));
    }

    @Test
    void resolve_ignoresFileChatFailureForConversationDashboardStatus() {
        VerlaConversation conversation = conversation(13L, 131L).build();
        VerlaTurn assignmentTurn = VerlaTurn.builder()
                .id(130L)
                .conversationId(13L)
                .status(TurnStatus.COMPLETED.name())
                .activeSessionId(1300L)
                .resolvedIntent("ASSIGNMENT")
                .build();
        VerlaTurn fileChatTurn = VerlaTurn.builder()
                .id(131L)
                .conversationId(13L)
                .status(TurnStatus.FAILED.name())
                .activeSessionId(1310L)
                .resolvedIntent("FILE_CHAT")
                .build();
        turnRepository.byId.put(130L, assignmentTurn);
        turnRepository.byId.put(131L, fileChatTurn);
        turnRepository.byConversation.put(13L, List.of(fileChatTurn, assignmentTurn));
        sessionRepository.byId.put(1300L, session(1300L, 130L, SessionStatus.SUCCEEDED));
        sessionRepository.byId.put(1310L, session(1310L, 131L, SessionStatus.FAILED));
        eventInboxRepository.add(13L, event(
                2L,
                13L,
                VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_COMPLETED,
                "{}"));
        eventInboxRepository.add(13L, event(
                3L,
                13L,
                VerlaAgentEventType.FILE_CHAT_FAILED,
                "{}"));

        assertEquals(
                VerlaConversationDashboardStatusService.STATUS_COMPLETED,
                service.resolve(conversation));
    }

    @Test
    void resolveAll_returnsStatusesByConversationId() {
        VerlaConversation completed = conversation(6L, 60L).build();
        VerlaConversation needsChoice = conversation(7L, 70L).build();
        turnRepository.byId.put(60L, turn(60L, 6L, TurnStatus.COMPLETED, null));
        turnRepository.byId.put(70L, turn(70L, 7L, TurnStatus.AWAITING_CLARIFY, null));

        Map<Long, String> result = service.resolveAll(List.of(completed, needsChoice));

        assertEquals(VerlaConversationDashboardStatusService.STATUS_COMPLETED, result.get(6L));
        assertEquals(VerlaConversationDashboardStatusService.STATUS_NEEDS_CHOICE, result.get(7L));
    }

    private static VerlaConversation.VerlaConversationBuilder conversation(Long id, Long lastTurnId) {
        return VerlaConversation.builder()
                .id(id)
                .status("active")
                .intentLifecycle(IntentLifecycle.COMMITTED.getDbValue())
                .lastTurnId(lastTurnId);
    }

    private static VerlaTurn turn(Long id, Long conversationId, TurnStatus status, Long activeSessionId) {
        return VerlaTurn.builder()
                .id(id)
                .conversationId(conversationId)
                .status(status.name())
                .activeSessionId(activeSessionId)
                .build();
    }

    private static VerlaSession session(Long id, Long turnId, SessionStatus status) {
        return VerlaSession.builder()
                .id(id)
                .turnId(turnId)
                .status(status.name())
                .build();
    }

    private static VerlaEventInbox event(
            Long id,
            Long conversationId,
            VerlaAgentEventType eventType,
            String payloadJson) {
        return VerlaEventInbox.builder()
                .id(id)
                .conversationId(conversationId)
                .eventType(eventType.name())
                .payloadJson(payloadJson)
                .status(VerlaEventInbox.STATUS_PROCESSED)
                .build();
    }

    private static class FakeEventInboxRepository implements VerlaEventInboxRepository {
        final Map<Long, List<VerlaEventInbox>> byConversation = new HashMap<>();

        void add(Long conversationId, VerlaEventInbox event) {
            byConversation.computeIfAbsent(conversationId, k -> new ArrayList<>()).add(event);
        }

        @Override
        public boolean tryInsert(VerlaEventInbox row) {
            add(row.getConversationId(), row);
            return true;
        }

        @Override
        public VerlaEventInbox findByMessageId(String messageId) {
            return null;
        }

        @Override
        public VerlaEventInbox findReady(Long sessionId, Long expectedSeq) {
            return null;
        }

        @Override
        public int markProcessed(Long id) {
            return 0;
        }

        @Override
        public int markSkipped(Long id, String reason) {
            return 0;
        }

        @Override
        public int markFailed(Long id, String reason) {
            return 0;
        }

        @Override
        public List<Long> findStuckSessions(int limit) {
            return List.of();
        }

        @Override
        public List<VerlaEventInbox> findReplayByConversation(Long conversationId, Long afterId, int limit) {
            return List.of();
        }

        @Override
        public List<VerlaEventInbox> findRecentProcessedByConversation(Long conversationId, int limit) {
            return byConversation.getOrDefault(conversationId, List.of()).stream()
                    .filter(event -> VerlaEventInbox.STATUS_PROCESSED.equals(event.getStatus()))
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public VerlaEventInbox findLatestProcessedBySession(Long sessionId) {
            return null;
        }
    }

    private static class FakeTurnRepository implements VerlaTurnRepository {
        final Map<Long, VerlaTurn> byId = new HashMap<>();
        final Map<Long, List<VerlaTurn>> byConversation = new HashMap<>();

        @Override
        public VerlaTurn save(VerlaTurn turn) {
            byId.put(turn.getId(), turn);
            return turn;
        }

        @Override
        public VerlaTurn findById(Long id) {
            return byId.get(id);
        }

        @Override
        public VerlaTurn findByIdForUpdate(Long id) {
            return byId.get(id);
        }

        @Override
        public List<VerlaTurn> findRecentByConversation(Long conversationId, int limit) {
            return byConversation.getOrDefault(conversationId, List.of()).stream()
                    .limit(limit)
                    .toList();
        }
    }

    private static class FakeSessionRepository implements VerlaSessionRepository {
        final Map<Long, VerlaSession> byId = new HashMap<>();
        final Map<Long, List<VerlaSession>> byTurn = new HashMap<>();

        @Override
        public VerlaSession save(VerlaSession session) {
            byId.put(session.getId(), session);
            return session;
        }

        @Override
        public VerlaSession findById(Long id) {
            return byId.get(id);
        }

        @Override
        public VerlaSession findByIdForUpdate(Long id) {
            return byId.get(id);
        }

        @Override
        public List<VerlaSession> findByTurn(Long turnId) {
            return byTurn.getOrDefault(turnId, List.of());
        }

        @Override
        public List<VerlaSession> findCompletedSiblings(Long turnId, Long excludeSessionId) {
            return List.of();
        }

        @Override
        public VerlaSession findByCorrelationId(String correlationId) {
            return null;
        }

        @Override
        public boolean bindQuotaLedger(Long sessionId, Long ledgerId, Long amount) {
            return true;
        }

        @Override
        public int countActiveAssignmentRuns() {
            return 0;
        }
    }

    private static class FakeClarifyFormRepository implements VerlaClarifyFormRepository {
        final Map<Long, List<VerlaClarifyForm>> openByConversation = new HashMap<>();

        @Override
        public VerlaClarifyForm upsertByFormId(VerlaClarifyForm form) {
            return form;
        }

        @Override
        public VerlaClarifyForm findByFormId(String formId) {
            return null;
        }

        @Override
        public VerlaClarifyForm findById(Long id) {
            return null;
        }

        @Override
        public List<VerlaClarifyForm> findOpenByConversation(Long conversationId) {
            return new ArrayList<>(openByConversation.getOrDefault(conversationId, List.of()));
        }

        @Override
        public int markSubmitted(String formId, Long submittedResponseId) {
            return 0;
        }

        @Override
        public int markStatus(String formId, String newStatus) {
            return 0;
        }
    }
}
