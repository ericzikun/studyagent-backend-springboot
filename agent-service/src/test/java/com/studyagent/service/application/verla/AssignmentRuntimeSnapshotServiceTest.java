package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.service.application.verla.dto.AssignmentRuntimeSnapshotView;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssignmentRuntimeSnapshotServiceTest {

    private FakeMessageRepository messageRepository;
    private FakeArtifactRepository artifactRepository;
    private FakeEventInboxRepository eventInboxRepository;
    private AssignmentRuntimeSnapshotService service;

    @BeforeEach
    void setUp() {
        messageRepository = new FakeMessageRepository();
        artifactRepository = new FakeArtifactRepository();
        eventInboxRepository = new FakeEventInboxRepository();
        service = new AssignmentRuntimeSnapshotService(
                messageRepository,
                artifactRepository,
                eventInboxRepository,
                new ObjectMapper());
    }

    @Test
    void getSnapshot_returnsSingleFormReadyStateAndChronologicalMessages() {
        messageRepository.messagesByConversation.put(42L, List.of(
                message(20L, 42L, "assistant", "need details"),
                message(10L, 42L, "user", "history essay")));
        eventInboxRepository.add(42L, event(
                1200L,
                42L,
                VerlaAgentEventType.ASSIGNMENT_CLARIFY_FORM_READY,
                "{\"payload\":{\"formId\":\"form_1\",\"requirementForm\":{\"subject\":\"History\",\"rubric\":\"hidden\"},\"appendAsk\":{\"questions\":[{\"id\":\"q1\",\"prompt\":\"Use sources?\"}]}}}"));

        AssignmentRuntimeSnapshotView snapshot = service.getSnapshot(42L);

        assertEquals(42L, snapshot.conversationId());
        assertEquals(1200L, snapshot.resumeAfterEventId());
        assertEquals(VerlaAgentEventType.ASSIGNMENT_CLARIFY_FORM_READY.name(), snapshot.stateEventType());
        assertEquals(List.of(10L, 20L), snapshot.payload().messages().stream().map(VerlaMessage::getId).toList());
        assertEquals("form_1", snapshot.payload().stateEventPayload().get("formId"));
        assertEquals(
                Map.of("subject", "History"),
                snapshot.payload().stateEventPayload().get("requirementForm"));
    }

    @Test
    void getSnapshot_returnsStateEventPayloadForDeepUnderstandingCompleted() {
        eventInboxRepository.add(84L, event(
                1367L,
                84L,
                VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED,
                """
                        {"payload":{
                          "ready":true,
                          "summary":"[Mock] Deep understanding ready",
                          "nextActions":["deep_understanding","generation"],
                          "userUnderstood":false,
                          "mockAutoPreview":true,
                          "isReadyForGeneration":false,
                          "requirementForm":{}
                        }}
                        """));

        AssignmentRuntimeSnapshotView snapshot = service.getSnapshot(84L);

        assertEquals(1367L, snapshot.resumeAfterEventId());
        assertEquals(VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED.name(),
                snapshot.stateEventType());
        assertEquals(true, snapshot.payload().stateEventPayload().get("ready"));
        assertEquals(false, snapshot.payload().stateEventPayload().get("isReadyForGeneration"));
        assertEquals("[Mock] Deep understanding ready",
                snapshot.payload().stateEventPayload().get("summary"));
    }

    @Test
    void getSnapshot_preservesClarifyCompletedAsStateEventType() {
        eventInboxRepository.add(86L, event(
                1500L,
                86L,
                VerlaAgentEventType.ASSIGNMENT_CLARIFY_STARTED,
                "{\"payload\":{\"summary\":\"started\"}}"));
        eventInboxRepository.add(86L, event(
                1501L,
                86L,
                VerlaAgentEventType.ASSIGNMENT_CLARIFY_COMPLETED,
                """
                        {"payload":{
                          "summary":"[Mock] Assignment requirements finalized",
                          "isReadyForGeneration":true,
                          "appendAskAnswers":[]
                        }}
                        """));

        AssignmentRuntimeSnapshotView snapshot = service.getSnapshot(86L);

        assertEquals(1501L, snapshot.resumeAfterEventId());
        assertEquals(VerlaAgentEventType.ASSIGNMENT_CLARIFY_COMPLETED.name(),
                snapshot.stateEventType());
        assertEquals(true, snapshot.payload().stateEventPayload().get("isReadyForGeneration"));
        assertEquals("[Mock] Assignment requirements finalized",
                snapshot.payload().stateEventPayload().get("summary"));
    }

    @Test
    void getSnapshot_ignoresNodeUpdatesForStateAndFoldsLatestAgentNodes() {
        eventInboxRepository.add(7L, event(
                100L,
                7L,
                VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                "{\"payload\":{\"node\":{\"id\":\"task-1\",\"taskName\":\"Research\",\"status\":\"running\",\"content\":\"Working\"}}}"));
        eventInboxRepository.add(7L, event(
                101L,
                7L,
                VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_FAILED,
                "{\"payload\":{\"error\":\"failed\"}}"));
        eventInboxRepository.add(7L, event(
                102L,
                7L,
                VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                "{\"payload\":{\"node\":{\"id\":\"task-1\",\"status\":\"failed\",\"content\":\"Task execution failed.\"}}}"));
        artifactRepository.artifactsByConversation.put(7L, List.of(
                VerlaArtifact.builder()
                        .id(1L)
                        .artifactUid("artifact_1")
                        .conversationId(7L)
                        .status("FAILED")
                        .build()));

        AssignmentRuntimeSnapshotView snapshot = service.getSnapshot(7L);

        assertEquals(102L, snapshot.resumeAfterEventId());
        assertEquals(VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_FAILED.name(), snapshot.stateEventType());
        assertEquals(1, snapshot.payload().agentNodes().size());
        Map<String, Object> node = snapshot.payload().agentNodes().get(0);
        assertEquals("task-1", node.get("id"));
        assertEquals("Research", node.get("taskName"));
        assertEquals("failed", node.get("status"));
        assertEquals("Task execution failed.", node.get("content"));
        assertEquals(1, snapshot.payload().artifacts().size());
    }

    private static VerlaMessage message(Long id, Long conversationId, String role, String text) {
        return VerlaMessage.builder()
                .id(id)
                .conversationId(conversationId)
                .role(role)
                .textContent(text)
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

    private static class FakeMessageRepository implements VerlaMessageRepository {
        private final Map<Long, List<VerlaMessage>> messagesByConversation = new HashMap<>();

        @Override
        public VerlaMessage save(VerlaMessage message) {
            return message;
        }

        @Override
        public VerlaMessage findById(Long id) {
            return null;
        }

        @Override
        public List<VerlaMessage> findByCursor(Long conversationId, Long cursor, int limit) {
            return messagesByConversation.getOrDefault(conversationId, List.of())
                    .stream()
                    .limit(limit)
                    .toList();
        }
    }

    private static class FakeArtifactRepository implements VerlaArtifactRepository {
        private final Map<Long, List<VerlaArtifact>> artifactsByConversation = new HashMap<>();

        @Override
        public VerlaArtifact findById(Long id) {
            return null;
        }

        @Override
        public VerlaArtifact findByUid(String artifactUid) {
            return null;
        }

        @Override
        public List<VerlaArtifact> findByConversation(Long conversationId) {
            return artifactsByConversation.getOrDefault(conversationId, List.of());
        }

        @Override
        public List<VerlaArtifact> findBySession(Long sessionId) {
            return List.of();
        }

        @Override
        public List<VerlaArtifact> findByUids(List<String> artifactUids) {
            return List.of();
        }

        @Override
        public VerlaArtifact upsertByUid(VerlaArtifact artifact) {
            return artifact;
        }
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
