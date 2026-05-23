package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AssignmentRuntimeProgressEstimatorTest {

    private FakeEventInboxRepository eventInboxRepository;
    private AssignmentRuntimeProgressEstimator estimator;

    @BeforeEach
    void setUp() {
        eventInboxRepository = new FakeEventInboxRepository();
        estimator = new AssignmentRuntimeProgressEstimator(new ObjectMapper(), eventInboxRepository);
    }

    @Test
    void estimateFromAgentNodes_usesCompletedAndRunningWeights() {
        List<Map<String, Object>> nodes = List.of(
                node("assignment-plan", "completed"),
                node("draft-writer", "running"),
                node("quality-check", "queued"));

        var estimate = estimator.estimateFromAgentNodes(nodes, LocalDateTime.now().minusSeconds(30));

        assertEquals("draft-writer", estimate.label());
        assertEquals(50.0, estimate.completePercent(), 0.01);
        assertEquals(600, estimate.estimatedRemainingSeconds());
    }

    @Test
    void resolveProgress_prefersExplicitPythonEtaOverComputedValue() {
        List<VerlaEventInbox> events = List.of(
                event(2L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"task-1\",\"status\":\"running\"}}}"),
                event(1L, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                        "{\"payload\":{\"progress\":{\"label\":\"Planning\",\"estimatedRemainingSeconds\":960}}}"));

        Map<String, Object> progress = estimator.resolveProgress(events);

        assertEquals("Planning", progress.get("label"));
        assertEquals(960, progress.get("estimatedRemainingSeconds"));
    }

    @Test
    void resolveProgress_computesEtaWhenPythonOmitsIt() {
        List<VerlaEventInbox> events = List.of(
                event(2L, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                        "{\"payload\":{\"node\":{\"id\":\"assignment-plan\",\"title\":\"Make plan\",\"status\":\"RUNNING\"}}}"),
                event(1L, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                        "{\"payload\":{\"stage\":\"assignment_run\"}}"));

        Map<String, Object> progress = estimator.resolveProgress(events);

        assertNotNull(progress);
        assertEquals("Make plan", progress.get("label"));
        assertEquals(600, progress.get("estimatedRemainingSeconds"));
    }

    @Test
    void resolveProgress_clearsEtaOnTerminalCompletion() {
        List<VerlaEventInbox> events = List.of(
                event(2L, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_COMPLETED,
                        "{\"payload\":{\"summary\":\"done\"}}"),
                event(1L, VerlaAgentEventType.AGENT_PROGRESS,
                        "{\"payload\":{\"progress\":{\"label\":\"QA\",\"estimatedRemainingSeconds\":120}}}"));

        Map<String, Object> progress = estimator.resolveProgress(events);

        assertEquals("Assignment ready", progress.get("label"));
        assertEquals(0, progress.get("estimatedRemainingSeconds"));
    }

    @Test
    void enrichAssignmentRunPayload_addsComputedProgressForSse() {
        eventInboxRepository.add(10L, event(
                1L,
                VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                "{\"payload\":{\"stage\":\"assignment_run\"}}"));

        Map<String, Object> payload = Map.of(
                "node", Map.of("id", "assignment-plan", "title", "Make plan", "status", "RUNNING"));
        VerlaEventInbox current = event(
                2L,
                VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                "{\"payload\":{\"node\":{\"id\":\"assignment-plan\",\"title\":\"Make plan\",\"status\":\"RUNNING\"}}}");

        Map<String, Object> enriched = estimator.enrichAssignmentRunPayload(
                current.getEventType(), payload, 10L, current);

        assertNotNull(enriched.get("progress"));
        @SuppressWarnings("unchecked")
        Map<String, Object> progress = (Map<String, Object>) enriched.get("progress");
        assertEquals("Make plan", progress.get("label"));
        assertEquals(600, progress.get("estimatedRemainingSeconds"));
    }

    @Test
    void resolveProgress_returnsNullWhenRunHasNotStarted() {
        assertNull(estimator.resolveProgress(List.of(
                event(1L, VerlaAgentEventType.ASSIGNMENT_CLARIFY_COMPLETED,
                        "{\"payload\":{\"isReadyForGeneration\":true}}"))));
    }

    private static Map<String, Object> node(String id, String status) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("title", id);
        node.put("status", status);
        return node;
    }

    private static VerlaEventInbox event(Long id, VerlaAgentEventType type, String payloadJson) {
        return VerlaEventInbox.builder()
                .id(id)
                .conversationId(10L)
                .eventType(type.name())
                .payloadJson(payloadJson)
                .receivedAt(LocalDateTime.now().minusMinutes(5))
                .status(VerlaEventInbox.STATUS_PROCESSED)
                .build();
    }

    private static class FakeEventInboxRepository implements VerlaEventInboxRepository {
        private final Map<Long, List<VerlaEventInbox>> eventsByConversation = new HashMap<>();

        void add(Long conversationId, VerlaEventInbox event) {
            eventsByConversation.computeIfAbsent(conversationId, key -> new ArrayList<>()).add(event);
        }

        @Override
        public boolean tryInsert(VerlaEventInbox row) {
            return false;
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
            return eventsByConversation.getOrDefault(conversationId, List.of())
                    .stream()
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .limit(limit)
                    .toList();
        }
    }
}
