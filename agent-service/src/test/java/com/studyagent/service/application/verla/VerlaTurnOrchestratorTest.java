package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import com.studyagent.service.domain.verla.state.SessionStateMachine;
import com.studyagent.service.domain.verla.state.SessionStatus;
import com.studyagent.service.domain.verla.state.TurnStateMachine;
import com.studyagent.service.domain.verla.state.TurnStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static final class FakeSessionRepository implements VerlaSessionRepository {
        VerlaSession session;
        VerlaSession saved;

        @Override
        public VerlaSession save(VerlaSession session) {
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
            return List.of();
        }
    }

    private static final class FakeMessageRepository implements VerlaMessageRepository {
        VerlaMessage saved;

        @Override
        public VerlaMessage save(VerlaMessage message) {
            this.saved = message;
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
        Long incrementedConversationId;

        @Override
        public VerlaConversation save(VerlaConversation conversation) {
            return conversation;
        }

        @Override
        public VerlaConversation findById(Long id) {
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
            return 1;
        }
    }
}
