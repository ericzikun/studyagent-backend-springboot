package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.studyagent.service.application.MqOutboxService;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.mq.MqOutboxRepository;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import com.studyagent.service.domain.verla.state.ConversationStateMachine;
import com.studyagent.service.domain.verla.state.ConversationStatus;
import com.studyagent.service.domain.verla.state.SessionStateMachine;
import com.studyagent.service.domain.verla.state.SessionStatus;
import com.studyagent.service.domain.verla.state.TurnStateMachine;
import com.studyagent.service.domain.verla.state.TurnStatus;
import com.studyagent.service.application.verla.dto.PlanConfirmResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerlaTurnOrchestratorTest {

    @Test
    void onAgentCompleted_does_not_persist_large_finalResult_as_message_text() {
        FakeSessionRepository sessionRepository = new FakeSessionRepository();
        FakeTurnRepository turnRepository = new FakeTurnRepository();
        FakeMessageRepository messageRepository = new FakeMessageRepository();
        FakeConversationRepository conversationRepository = new FakeConversationRepository();
        VerlaTurnOrchestrator orchestrator = new VerlaTurnOrchestrator(
                null,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                new TurnStateMachine(),
                new SessionStateMachine(),
                null,
                new ObjectMapper());

        sessionRepository.session = VerlaSession.builder()
                .id(357L)
                .conversationId(153L)
                .turnId(153L)
                .status(SessionStatus.RUNNING.name())
                .build();
        turnRepository.turn = VerlaTurn.builder()
                .id(153L)
                .conversationId(153L)
                .status(TurnStatus.RUNNING_AGENT.name())
                .build();
        String finalResult = "x".repeat(66_000);
        Map<String, Object> payload = Map.of(
                "finalResult", finalResult,
                "artifactPaths", List.of("assignment_output.md"),
                "runId", "run_test");

        orchestrator.onAgentCompleted(357L, payload);

        VerlaMessage savedMessage = messageRepository.saved;
        assertNotNull(savedMessage);
        assertEquals("Assignment output is ready. Open the generated artifact to view the full result.",
                savedMessage.getTextContent());
        assertFalse(savedMessage.getBlocksJson().contains(finalResult));
        assertTrue(savedMessage.getBlocksJson().contains("\"finalResultTruncated\":true"));
        assertEquals(SessionStatus.SUCCEEDED.name(), sessionRepository.saved.getStatus());
        assertEquals(TurnStatus.COMPLETED.name(), turnRepository.saved.getStatus());
        assertEquals(153L, conversationRepository.incrementedConversationId);
    }

    @Test
    void confirmLatestPlan_when_assignment_rejected_continues_deep_understanding() {
        FakeSessionRepository sessionRepository = new FakeSessionRepository();
        FakeTurnRepository turnRepository = new FakeTurnRepository();
        FakeMessageRepository messageRepository = new FakeMessageRepository();
        FakeConversationRepository conversationRepository = new FakeConversationRepository();
        FakeMqOutboxRepository mqOutboxRepository = new FakeMqOutboxRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MqOutboxService mqOutboxService = new MqOutboxService(
                mqOutboxRepository,
                event -> { },
                objectMapper);
        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository,
                messageRepository,
                new ConversationStateMachine());
        VerlaTurnOrchestrator orchestrator = new VerlaTurnOrchestrator(
                conversationService,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                new TurnStateMachine(),
                new SessionStateMachine(),
                mqOutboxService,
                objectMapper);

        conversationRepository.conversation = VerlaConversation.builder()
                .id(99L)
                .userId("user_1")
                .status(ConversationStatus.ACTIVE.getDbValue())
                .primaryIntent("ASSIGNMENT")
                .build();
        turnRepository.turn = VerlaTurn.builder()
                .id(7L)
                .conversationId(99L)
                .userMessageId(8L)
                .planSessionId(9L)
                .status(TurnStatus.DISPATCHING.name())
                .resolvedIntent("ASSIGNMENT")
                .build();

        PlanConfirmResult result = orchestrator.confirmLatestPlan(
                "user_1",
                99L,
                false,
                null);

        assertTrue(result.isSuccess());
        assertEquals("deep_understanding", result.getNextStage());
        assertEquals("/dashboard/create?type=assignment&surface=understanding&stream=verla&cid=99",
                result.getRedirectUrl());
        assertNull(result.getMessageResult().getSkipPlanReason());
        assertEquals(1001L, result.getMessageResult().getAgentSessionId());
        assertEquals("ASSIGNMENT", conversationRepository.conversation.getPrimaryIntent());
        assertEquals(TurnStatus.RUNNING_AGENT.name(), turnRepository.saved.getStatus());
        assertEquals(1, messageRepository.savedMessages.size());
        assertEquals("user", messageRepository.savedMessages.get(0).getRole());
        assertEquals("No, let’s keep chatting.", messageRepository.savedMessages.get(0).getTextContent());
        assertTrue(conversationRepository.incrementVersionCount >= 1);
        assertEquals(SessionStatus.DISPATCHING.name(), sessionRepository.saved.getStatus());
        assertNotNull(mqOutboxRepository.saved);
        assertEquals("cmd.assignment.deep_understanding", mqOutboxRepository.saved.getAction());
        assertEquals("cmd.assignment.deep_understanding", mqOutboxRepository.saved.getRoutingKey());
        assertTrue(mqOutboxRepository.saved.getPayload().contains("\"userUnderstood\":false"));
    }

    private static final class FakeSessionRepository implements VerlaSessionRepository {
        VerlaSession session;
        VerlaSession saved;

        @Override
        public VerlaSession save(VerlaSession session) {
            if (session.getId() == null) {
                session.setId(1001L);
            }
            this.saved = session;
            return session;
        }

        @Override
        public VerlaSession findById(Long id) {
            return session;
        }

        @Override
        public VerlaSession findByIdForUpdate(Long id) {
            return session;
        }

        @Override
        public List<VerlaSession> findByTurn(Long turnId) {
            return List.of();
        }

        @Override
        public List<VerlaSession> findCompletedSiblings(Long turnId, Long excludeSessionId) {
            return List.of();
        }

        @Override
        public VerlaSession findByCorrelationId(String correlationId) {
            return null;
        }
    }

    private static final class FakeTurnRepository implements VerlaTurnRepository {
        VerlaTurn turn;
        VerlaTurn saved;

        @Override
        public VerlaTurn save(VerlaTurn turn) {
            this.saved = turn;
            return turn;
        }

        @Override
        public VerlaTurn findById(Long id) {
            return turn;
        }

        @Override
        public VerlaTurn findByIdForUpdate(Long id) {
            return turn;
        }

        @Override
        public List<VerlaTurn> findRecentByConversation(Long conversationId, int limit) {
            if (turn != null && turn.getConversationId().equals(conversationId)) {
                return List.of(turn);
            }
            return List.of();
        }
    }

    private static final class FakeMessageRepository implements VerlaMessageRepository {
        VerlaMessage saved;
        List<VerlaMessage> savedMessages = new ArrayList<>();

        @Override
        public VerlaMessage save(VerlaMessage message) {
            this.saved = message;
            this.savedMessages.add(message);
            return message;
        }

        @Override
        public VerlaMessage findById(Long id) {
            return saved;
        }

        @Override
        public List<VerlaMessage> findByCursor(Long conversationId, Long cursor, int limit) {
            return List.of();
        }
    }

    private static final class FakeConversationRepository implements VerlaConversationRepository {
        VerlaConversation conversation;
        Long incrementedConversationId;
        int incrementVersionCount;

        @Override
        public VerlaConversation save(VerlaConversation conversation) {
            this.conversation = conversation;
            return conversation;
        }

        @Override
        public VerlaConversation findById(Long id) {
            if (conversation != null && conversation.getId().equals(id)) {
                return conversation;
            }
            return null;
        }

        @Override
        public List<VerlaConversation> findByUserFilteredPaged(
                String userId, String segmentQueryKey, String conversationStatusDb, int page, int size) {
            return List.of();
        }

        @Override
        public long countByUserFiltered(String userId, String segmentQueryKey, String conversationStatusDb) {
            return 0;
        }

        @Override
        public int touchOnNewTurn(Long id, Long turnId) {
            return 0;
        }

        @Override
        public int incrementVersion(Long id) {
            this.incrementedConversationId = id;
            this.incrementVersionCount += 1;
            return 1;
        }
    }

    private static final class FakeMqOutboxRepository implements MqOutboxRepository {
        MqOutbox saved;

        @Override
        public MqOutbox save(MqOutbox mqOutbox) {
            this.saved = mqOutbox;
            return mqOutbox;
        }

        @Override
        public MqOutbox findById(Long id) {
            return saved;
        }

        @Override
        public MqOutbox findByEventId(String eventId) {
            return saved;
        }

        @Override
        public List<MqOutbox> findPendingMessages(int limit, LocalDateTime currentTime) {
            return saved == null ? List.of() : List.of(saved);
        }

        @Override
        public void markAsSent(Long id) {
        }

        @Override
        public void markForRetry(Long id, String errorMessage, LocalDateTime nextRetryAt) {
        }

        @Override
        public void markAsFailed(Long id, String errorMessage) {
        }
    }
}
