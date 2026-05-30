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
                new NoopAttachmentRepository(),
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
    void onAgentCompleted_persists_message_role_from_payload() {
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
                new NoopAttachmentRepository(),
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

        orchestrator.onAgentCompleted(357L, Map.of(
                "role", "system",
                "finalResult", "Done"));

        assertNotNull(messageRepository.saved);
        assertEquals("system", messageRepository.saved.getRole());
        assertEquals("Done", messageRepository.saved.getTextContent());
    }

    @Test
    void onAgentFailed_persists_message_role_from_payload() {
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
                new NoopAttachmentRepository(),
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

        orchestrator.onAgentFailed(357L, Map.of(
                "role", "system",
                "errorMessage", "Run failed"));

        assertNotNull(messageRepository.saved);
        assertEquals("system", messageRepository.saved.getRole());
        assertEquals("Run failed", messageRepository.saved.getTextContent());
        assertEquals(SessionStatus.FAILED.name(), sessionRepository.saved.getStatus());
        assertEquals(TurnStatus.FAILED.name(), turnRepository.saved.getStatus());
    }

    @Test
    void confirmLatestPlan_when_assignment_rejected_triggers_new_plan_intent() {
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
                new NoopAttachmentRepository(),
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
                "No, let’s keep chatting.");

        assertTrue(result.isSuccess());
        assertEquals("planning", result.getNextStage());
        assertNull(result.getRedirectUrl());
        assertNull(result.getMessageResult().getSkipPlanReason());
        assertNull(result.getMessageResult().getAgentSessionId());
        assertEquals(1001L, result.getMessageResult().getPlanSessionId());
        assertEquals("ASSIGNMENT", conversationRepository.conversation.getPrimaryIntent());
        assertEquals(TurnStatus.COMPLETED.name(), turnRepository.savedTurns.get(0).getStatus());
        assertEquals(TurnStatus.PLANNING.name(), turnRepository.saved.getStatus());
        assertEquals(1, messageRepository.savedMessages.size());
        assertEquals("user", messageRepository.savedMessages.get(0).getRole());
        assertEquals("No, let’s keep chatting.", messageRepository.savedMessages.get(0).getTextContent());
        assertEquals(0, conversationRepository.incrementVersionCount);
        assertEquals(SessionStatus.DISPATCHING.name(), sessionRepository.saved.getStatus());
        MqOutbox planCommand = mqOutboxRepository.findSavedByAction("cmd.plan.intent");
        assertNotNull(planCommand);
        assertEquals("cmd.plan.intent", planCommand.getAction());
        assertEquals("cmd.plan.intent", planCommand.getRoutingKey());
        assertTrue(planCommand.getPayload().contains("No, let’s keep chatting."));
        assertTrue(planCommand.getPayload().contains("\"planConfirmRejected\":true"));
        assertFalse(planCommand.getPayload().contains("cmd.assignment.deep_understanding"));
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
        List<VerlaTurn> savedTurns = new ArrayList<>();
        long nextId = 100L;

        @Override
        public VerlaTurn save(VerlaTurn turn) {
            if (turn.getId() == null) {
                turn.setId(nextId++);
            }
            this.saved = turn;
            this.savedTurns.add(turn);
            this.turn = turn;
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
        long nextId = 200L;

        @Override
        public VerlaMessage save(VerlaMessage message) {
            if (message.getId() == null) {
                message.setId(nextId++);
            }
            this.saved = message;
            for (int i = 0; i < savedMessages.size(); i++) {
                if (message.getId().equals(savedMessages.get(i).getId())) {
                    savedMessages.set(i, message);
                    return message;
                }
            }
            savedMessages.add(message);
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

        @Override
        public List<VerlaMessage> findFileChatByCursor(Long conversationId, String objectId, Long cursor, int limit) {
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

        @Override
        public int updateTitle(Long id, String title) {
            return 0;
        }
    }

    private static final class NoopAttachmentRepository implements com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository {
        @Override
        public com.studyagent.service.domain.verla.VerlaAttachment save(com.studyagent.service.domain.verla.VerlaAttachment attachment) {
            return attachment;
        }

        @Override
        public com.studyagent.service.domain.verla.VerlaAttachment findById(Long id) {
            return null;
        }

        @Override
        public com.studyagent.service.domain.verla.VerlaAttachment findByObjectId(String objectId) {
            return null;
        }

        @Override
        public java.util.List<com.studyagent.service.domain.verla.VerlaAttachment> findByObjectIds(java.util.List<String> objectIds) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<com.studyagent.service.domain.verla.VerlaAttachment> listByConversation(Long conversationId, int limit) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<com.studyagent.service.domain.verla.VerlaAttachment> listByTurn(Long turnId) {
            return java.util.List.of();
        }

        @Override
        public com.studyagent.service.domain.verla.VerlaAttachment updateParseProgress(com.studyagent.service.domain.verla.VerlaAttachment patch) {
            return patch;
        }

        @Override
        public com.studyagent.service.domain.verla.VerlaAttachment updateByObjectIdSelective(com.studyagent.service.domain.verla.VerlaAttachment patch) {
            return patch;
        }
    }

    private static final class FakeMqOutboxRepository implements MqOutboxRepository {
        MqOutbox saved;
        List<MqOutbox> savedMessages = new ArrayList<>();

        @Override
        public MqOutbox save(MqOutbox mqOutbox) {
            this.saved = mqOutbox;
            this.savedMessages.add(mqOutbox);
            return mqOutbox;
        }

        MqOutbox findSavedByAction(String action) {
            return savedMessages.stream()
                    .filter(message -> action.equals(message.getAction()))
                    .findFirst()
                    .orElse(null);
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
        public List<MqOutbox> claimPendingMessages(
                int limit,
                String workerId,
                LocalDateTime currentTime,
                LocalDateTime leaseUntil) {
            return findPendingMessages(limit, currentTime);
        }

        @Override
        public MqOutbox claimMessage(
                Long id,
                String workerId,
                LocalDateTime currentTime,
                LocalDateTime leaseUntil) {
            return saved != null && saved.getId() != null && saved.getId().equals(id) ? saved : null;
        }

        @Override
        public void markAsSent(Long id) {
        }

        @Override
        public void markAsSent(Long id, String workerId) {
        }

        @Override
        public void markForRetry(Long id, String errorMessage, LocalDateTime nextRetryAt) {
        }

        @Override
        public void markForRetry(Long id, String workerId, String errorMessage, LocalDateTime nextRetryAt) {
        }

        @Override
        public void markAsFailed(Long id, String errorMessage) {
        }

        @Override
        public void markAsFailed(Long id, String workerId, String errorMessage) {
        }
    }
}
