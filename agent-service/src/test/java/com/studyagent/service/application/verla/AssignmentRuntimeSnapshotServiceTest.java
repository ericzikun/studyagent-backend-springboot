package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.service.application.verla.dto.AssignmentRuntimeSnapshotView;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaWorkforceTaskOutput;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import com.studyagent.service.domain.verla.WorkforceTaskProgressSnapshot;
import com.studyagent.service.domain.verla.VerlaWorkforceTask;
import com.studyagent.service.domain.verla.repo.VerlaWorkforceTaskOutputRepository;
import com.studyagent.service.domain.verla.repo.VerlaWorkforceTaskRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AssignmentRuntimeSnapshotServiceTest {

    private FakeMessageRepository messageRepository;
    private FakeArtifactRepository artifactRepository;
    private FakeEventInboxRepository eventInboxRepository;
    private FakeWorkforceTaskOutputRepository taskOutputRepository;
    private FakeWorkforceTaskRepository workforceTaskRepository;
    private AssignmentRuntimeSnapshotService service;

    @BeforeEach
    void setUp() {
        messageRepository = new FakeMessageRepository();
        artifactRepository = new FakeArtifactRepository();
        eventInboxRepository = new FakeEventInboxRepository();
        taskOutputRepository = new FakeWorkforceTaskOutputRepository();
        workforceTaskRepository = new FakeWorkforceTaskRepository();
        AssignmentRuntimeProgressEstimator progressEstimator =
                new AssignmentRuntimeProgressEstimator(
                        new ObjectMapper(),
                        eventInboxRepository,
                        workforceTaskRepository);
        service = new AssignmentRuntimeSnapshotService(
                messageRepository,
                artifactRepository,
                eventInboxRepository,
                progressEstimator,
                taskOutputRepository,
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

    @SuppressWarnings("unchecked")
    @Test
    void getSnapshot_embedsPersistedNodeDetailsInAgentNodes() {
        eventInboxRepository.add(17L, event(
                300L,
                17L,
                88L,
                VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                "{\"payload\":{\"node\":{\"id\":\"task-1\",\"taskName\":\"Draft\",\"status\":\"completed\",\"content\":\"Card text\"}}}"));
        taskOutputRepository.outputsBySession.put(88L, List.of(
                VerlaWorkforceTaskOutput.builder()
                        .sessionId(88L)
                        .nodeId("task-1")
                        .resultText("Recovered detail transcript")
                        .detailItemsJson("[{\"type\":\"search\",\"detailed\":[{\"name\":\"Google Search\"}]}]")
                        .build()));

        AssignmentRuntimeSnapshotView snapshot = service.getSnapshot(17L);

        Map<String, Object> node = snapshot.payload().agentNodes().get(0);
        assertEquals("Card text", node.get("content"));
        Map<String, Object> detailed = (Map<String, Object>) node.get("detailed");
        assertEquals("Recovered detail transcript", detailed.get("content"));
        List<Map<String, Object>> detailItems = (List<Map<String, Object>>) detailed.get("detailItems");
        assertEquals("search", detailItems.get(0).get("type"));
    }

    @Test
    void getSnapshot_computesProgressWhenLatestNodeEventOmitsExplicitEta() {
        LocalDateTime startedAt = LocalDateTime.now().minusSeconds(30);
        eventInboxRepository.add(90L, event(
                2000L,
                90L,
                90L,
                startedAt,
                VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                """
                        {"payload":{
                          "stage":"assignment_run",
                          "progress":{"label":"Planning assignment","estimatedRemainingSeconds":960}
                        }}
                        """));
        eventInboxRepository.add(90L, event(
                2001L,
                90L,
                90L,
                startedAt,
                VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                "{\"payload\":{\"node\":{\"id\":\"assignment-plan\",\"title\":\"Make plan\",\"status\":\"RUNNING\"}}}"));

        AssignmentRuntimeSnapshotView snapshot = service.getSnapshot(90L);

        assertEquals(2001L, snapshot.resumeAfterEventId());
        assertEquals("Make plan", snapshot.payload().progress().get("label"));
        assertEquals(90, snapshot.payload().progress().get("estimatedRemainingSeconds"));
    }

    @Test
    void getSnapshot_normalizesEquivalentEtaPayloadFields() {
        workforceTaskRepository.snapshotBySession.put(91L, new WorkforceTaskProgressSnapshot(3, 1, 1, null));
        eventInboxRepository.add(91L, event(
                2100L,
                91L,
                91L,
                null,
                VerlaAgentEventType.AGENT_PROGRESS,
                "{\"payload\":{\"label\":\"Drafting\",\"estRemainingTimeSeconds\":\"645\"}}"));

        AssignmentRuntimeSnapshotView snapshot = service.getSnapshot(91L);

        assertEquals("Drafting", snapshot.payload().progress().get("label"));
        assertEquals(645, snapshot.payload().progress().get("estimatedRemainingSeconds"));
    }

    @Test
    void getSnapshot_clearsPositiveProgressEtaOnTerminalEvent() {
        eventInboxRepository.add(92L, event(
                2200L,
                92L,
                VerlaAgentEventType.AGENT_PROGRESS,
                "{\"payload\":{\"progress\":{\"label\":\"QA\",\"estimatedRemainingSeconds\":120}}}"));
        eventInboxRepository.add(92L, event(
                2201L,
                92L,
                VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_COMPLETED,
                "{\"payload\":{\"summary\":\"done\"}}"));

        AssignmentRuntimeSnapshotView snapshot = service.getSnapshot(92L);

        assertEquals("Assignment ready", snapshot.payload().progress().get("label"));
        assertEquals(0, snapshot.payload().progress().get("estimatedRemainingSeconds"));
    }

    @Test
    void getSnapshot_computesProgressWhenEtaFieldsAreMissing() {
        eventInboxRepository.add(93L, event(
                2300L,
                93L,
                VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                "{\"payload\":{\"stage\":\"assignment_run\"}}"));
        eventInboxRepository.add(93L, event(
                2301L,
                93L,
                VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                "{\"payload\":{\"node\":{\"id\":\"assignment-plan\",\"title\":\"Make plan\",\"status\":\"RUNNING\"}}}"));

        AssignmentRuntimeSnapshotView snapshot = service.getSnapshot(93L);

        assertEquals("Make plan", snapshot.payload().progress().get("label"));
        assertEquals(120, snapshot.payload().progress().get("estimatedRemainingSeconds"));
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
        return event(id, conversationId, null, null, eventType, payloadJson);
    }

    private static VerlaEventInbox event(
            Long id,
            Long conversationId,
            Long sessionId,
            LocalDateTime receivedAt,
            VerlaAgentEventType eventType,
            String payloadJson) {
        return VerlaEventInbox.builder()
                .id(id)
                .conversationId(conversationId)
                .sessionId(sessionId)
                .eventType(eventType.name())
                .payloadJson(payloadJson)
                .receivedAt(receivedAt)
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

        @Override
        public List<VerlaMessage> findFileChatByCursor(Long conversationId, String objectId, Long cursor, int limit) {
            return List.of();
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

    private static class FakeWorkforceTaskRepository implements VerlaWorkforceTaskRepository {
        private final Map<Long, WorkforceTaskProgressSnapshot> snapshotBySession = new HashMap<>();

        @Override
        public Optional<VerlaWorkforceTask> findBySessionAndNode(Long sessionId, String nodeId) {
            return Optional.empty();
        }

        @Override
        public List<VerlaWorkforceTask> listBySession(Long sessionId) {
            return List.of();
        }

        @Override
        public List<VerlaWorkforceTask> listByConversation(Long conversationId) {
            return List.of();
        }

        @Override
        public WorkforceTaskProgressSnapshot aggregateProgressBySession(Long sessionId) {
            return snapshotBySession.getOrDefault(sessionId, WorkforceTaskProgressSnapshot.empty());
        }

        @Override
        public VerlaWorkforceTask upsertBySessionNode(VerlaWorkforceTask patch) {
            return patch;
        }
    }

    private static class FakeWorkforceTaskOutputRepository implements VerlaWorkforceTaskOutputRepository {
        private final Map<Long, List<VerlaWorkforceTaskOutput>> outputsBySession = new HashMap<>();

        @Override
        public Optional<VerlaWorkforceTaskOutput> findBySessionAndNode(Long sessionId, String nodeId) {
            return outputsBySession.getOrDefault(sessionId, List.of())
                    .stream()
                    .filter(output -> nodeId.equals(output.getNodeId()))
                    .findFirst();
        }

        @Override
        public List<VerlaWorkforceTaskOutput> listBySession(Long sessionId) {
            return outputsBySession.getOrDefault(sessionId, List.of());
        }

        @Override
        public VerlaWorkforceTaskOutput upsertBySessionNode(VerlaWorkforceTaskOutput patch) {
            return patch;
        }
    }
}
