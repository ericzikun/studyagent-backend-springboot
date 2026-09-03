package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
import com.studyagent.service.application.MqOutboxService;
import com.studyagent.service.application.verla.entitlement.EntitlementService;
import com.studyagent.service.application.verla.dto.SendMessageCommand;
import com.studyagent.service.application.verla.quota.VerlaQuotaService;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import com.studyagent.service.domain.verla.state.SessionStateMachine;
import com.studyagent.service.domain.verla.state.TurnStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VerlaTurnOrchestratorContextRefTest {

    private static final String USER_ID = "user_1";
    private static final Long CONVERSATION_ID = 1001L;

    private FakeConversationRepository conversationRepository;
    private FakeTurnRepository turnRepository;
    private FakeSessionRepository sessionRepository;
    private FakeMessageRepository messageRepository;
    private CapturingMqOutboxService mqOutboxService;
    private VerlaTurnOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        conversationRepository = new FakeConversationRepository();
        turnRepository = new FakeTurnRepository();
        sessionRepository = new FakeSessionRepository();
        messageRepository = new FakeMessageRepository();
        mqOutboxService = new CapturingMqOutboxService();

        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository, messageRepository, null, new com.fasterxml.jackson.databind.ObjectMapper());

        orchestrator = new VerlaTurnOrchestrator(
                conversationService,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                new NoopAttachmentRepository(),
                Mockito.mock(com.studyagent.service.domain.verla.repo.VerlaArtifactRepository.class),
                null,
                new TurnStateMachine(),
                new SessionStateMachine(),
                mqOutboxService,
                new ObjectMapper(),
                Mockito.mock(VerlaQuotaService.class),
                Mockito.mock(EntitlementService.class),
                null,
                event -> {},
                Mockito.mock(com.studyagent.common.analytics.AnalyticsService.class));
        ReflectionTestUtils.setField(orchestrator, "commandExchange", "studyagent.command");
    }

    @Test
    void onUserMessage_shouldUseVersionAfterTouchOnNewTurn() {
        conversationRepository.put(conversation(1L, null));

        orchestrator.onUserMessage(SendMessageCommand.builder()
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .text("帮我分析题目")
                .build());

        assertThat(contextRefConvVersion()).isEqualTo(2L);
    }

    @Test
    void firstUserMessage_shouldIncludeUploadedAttachmentsInTaskNameCommand() {
        conversationRepository.put(conversation(1L, null));

        orchestrator.onUserMessage(SendMessageCommand.builder()
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .text("帮我分析这份作业")
                .attachmentsJson("""
                        [
                          {"objectId":"att_1","filename":"assignment.pdf","mime":"application/pdf","sizeBytes":12345},
                          {"objectId":"att_2","filename":"rubric.docx","mime":"application/vnd.openxmlformats-officedocument.wordprocessingml.document","sizeBytes":23456}
                        ]
                        """)
                .build());

        VerlaCommandEnvelope taskNameEnvelope = mqOutboxService.envelopes.stream()
                .filter(envelope -> "cmd.plan.task_name".equals(envelope.getAction()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected cmd.plan.task_name"));

        assertThat(taskNameEnvelope.getPayload().get("objectIds"))
                .isEqualTo(List.of("att_1", "att_2"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments =
                (List<Map<String, Object>>) taskNameEnvelope.getPayload().get("attachments");
        assertThat(attachments).hasSize(2);
        assertThat(attachments.get(0))
                .containsEntry("objectId", "att_1")
                .containsEntry("filename", "assignment.pdf")
                .containsEntry("mime", "application/pdf")
                .containsEntry("sizeBytes", 12345);
    }

    @Test
    void forcedCapability_shouldUseVersionAfterAllConversationBumps() {
        conversationRepository.put(conversation(1L, null));

        orchestrator.onUserMessage(SendMessageCommand.builder()
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .text("直接走检测")
                .forceIntent("AI_DETECTION")
                .build());

        assertThat(contextRefConvVersion()).isEqualTo(3L);
    }

    @Test
    void continueAssignmentClarify_shouldUseIncrementedConversationVersion() {
        conversationRepository.put(conversation(5L, "ASSIGNMENT"));
        VerlaTurn turn = turnRepository.save(VerlaTurn.builder()
                .conversationId(CONVERSATION_ID)
                .userMessageId(501L)
                .status("RUNNING_AGENT")
                .resolvedIntent("ASSIGNMENT")
                .resolvedSlotsJson("{}")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        sessionRepository.save(VerlaSession.builder()
                .conversationId(CONVERSATION_ID)
                .turnId(turn.getId())
                .kind("ASSIGNMENT")
                .status("SUCCEEDED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        messageRepository.save(VerlaMessage.builder()
                .conversationId(CONVERSATION_ID)
                .turnId(turn.getId())
                .role("user")
                .textContent("原始问题")
                .createdAt(LocalDateTime.now())
                .build());

        Long previousSessionId = sessionRepository.latestSessionId();
        orchestrator.continueAssignmentClarify(
                USER_ID,
                CONVERSATION_ID,
                previousSessionId,
                "need_more_help",
                false,
                "我还是没明白",
                List.of("obj_1"),
                null);

        assertThat(contextRefConvVersion()).isEqualTo(6L);
    }

    private VerlaConversation conversation(Long version, String primaryIntent) {
        return VerlaConversation.builder()
                .id(CONVERSATION_ID)
                .userId(USER_ID)
                .title("作业辅导")
                .status("active")
                .primaryIntent(primaryIntent)
                .workspaceJson("{}")
                .turnCount(0)
                .version(version)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private long contextRefConvVersion() {
        VerlaCommandEnvelope envelopeWithContextRef = mqOutboxService.envelopes.stream()
                .filter(envelope -> envelope.getPayload() != null
                        && envelope.getPayload().get("contextRef") instanceof Map<?, ?>)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("Expected an outbox command with contextRef"));
        @SuppressWarnings("unchecked")
        Map<String, Object> contextRef = (Map<String, Object>) envelopeWithContextRef.getPayload().get("contextRef");
        return ((Number) contextRef.get("convVersion")).longValue();
    }

    private static VerlaConversation copyConversation(VerlaConversation conversation) {
        if (conversation == null) {
            return null;
        }
        return VerlaConversation.builder()
                .id(conversation.getId())
                .userId(conversation.getUserId())
                .title(conversation.getTitle())
                .status(conversation.getStatus())
                .primaryIntent(conversation.getPrimaryIntent())
                .workspaceJson(conversation.getWorkspaceJson())
                .turnCount(conversation.getTurnCount())
                .lastTurnId(conversation.getLastTurnId())
                .lastMessageAt(conversation.getLastMessageAt())
                .version(conversation.getVersion())
                .createdAt(conversation.getCreatedAt())
                .updatedAt(conversation.getUpdatedAt())
                .build();
    }

    private static VerlaTurn copyTurn(VerlaTurn turn) {
        if (turn == null) {
            return null;
        }
        return VerlaTurn.builder()
                .id(turn.getId())
                .conversationId(turn.getConversationId())
                .userMessageId(turn.getUserMessageId())
                .status(turn.getStatus())
                .resolvedIntent(turn.getResolvedIntent())
                .resolvedSlotsJson(turn.getResolvedSlotsJson())
                .activeSessionId(turn.getActiveSessionId())
                .planSessionId(turn.getPlanSessionId())
                .agentSessionId(turn.getAgentSessionId())
                .totalSteps(turn.getTotalSteps())
                .completedSteps(turn.getCompletedSteps())
                .lastProgressAt(turn.getLastProgressAt())
                .startedAt(turn.getStartedAt())
                .endedAt(turn.getEndedAt())
                .errorJson(turn.getErrorJson())
                .createdAt(turn.getCreatedAt())
                .updatedAt(turn.getUpdatedAt())
                .build();
    }

    private static VerlaSession copySession(VerlaSession session) {
        if (session == null) {
            return null;
        }
        return VerlaSession.builder()
                .id(session.getId())
                .conversationId(session.getConversationId())
                .turnId(session.getTurnId())
                .kind(session.getKind())
                .featureCode(session.getFeatureCode())
                .status(session.getStatus())
                .correlationId(session.getCorrelationId())
                .contextRefJson(session.getContextRefJson())
                .resultJson(session.getResultJson())
                .errorJson(session.getErrorJson())
                .expectedSeq(session.getExpectedSeq())
                .lastEventSeq(session.getLastEventSeq())
                .lastProgressAt(session.getLastProgressAt())
                .startedAt(session.getStartedAt())
                .endedAt(session.getEndedAt())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    private static VerlaMessage copyMessage(VerlaMessage message) {
        if (message == null) {
            return null;
        }
        return VerlaMessage.builder()
                .id(message.getId())
                .conversationId(message.getConversationId())
                .turnId(message.getTurnId())
                .sourceSessionId(message.getSourceSessionId())
                .role(message.getRole())
                .textContent(message.getTextContent())
                .blocksJson(message.getBlocksJson())
                .attachmentsJson(message.getAttachmentsJson())
                .metaJson(message.getMetaJson())
                .createdAt(message.getCreatedAt())
                .build();
    }

    private static class FakeConversationRepository implements VerlaConversationRepository {
        private final Map<Long, VerlaConversation> store = new HashMap<>();

        void put(VerlaConversation conversation) {
            store.put(conversation.getId(), copyConversation(conversation));
        }

        @Override
        public VerlaConversation save(VerlaConversation conversation) {
            store.put(conversation.getId(), copyConversation(conversation));
            return copyConversation(conversation);
        }

        @Override
        public VerlaConversation findById(Long id) {
            return copyConversation(store.get(id));
        }

        @Override
        public List<VerlaConversation> findByUserFilteredPaged(String userId, String segmentQueryKey,
                                                               String conversationStatusDb, int page, int size) {
            return List.of();
        }

        @Override
        public long countByUserFiltered(String userId, String segmentQueryKey, String conversationStatusDb) {
            return 0;
        }

        @Override
        public int touchOnNewTurn(Long id, Long turnId) {
            VerlaConversation stored = store.get(id);
            stored.setVersion(stored.getVersion() + 1);
            stored.setLastTurnId(turnId);
            stored.setTurnCount((stored.getTurnCount() == null ? 0 : stored.getTurnCount()) + 1);
            stored.setLastMessageAt(LocalDateTime.now());
            stored.setUpdatedAt(LocalDateTime.now());
            return 1;
        }

        @Override
        public int incrementVersion(Long id) {
            VerlaConversation stored = store.get(id);
            stored.setVersion(stored.getVersion() + 1);
            stored.setUpdatedAt(LocalDateTime.now());
            return 1;
        }

        @Override
        public int updateTitle(Long id, String title) {
            return 0;
        }
    }

    private static class NoopAttachmentRepository implements com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository {
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
        public List<com.studyagent.service.domain.verla.VerlaAttachment> findByObjectIds(List<String> objectIds) {
            return List.of();
        }

        @Override
        public List<com.studyagent.service.domain.verla.VerlaAttachment> listByConversation(Long conversationId, int limit) {
            return List.of();
        }

        @Override
        public List<com.studyagent.service.domain.verla.VerlaAttachment> listByTurn(Long turnId) {
            return List.of();
        }

        @Override
        public long countActiveUserUploadsForConversation(Long conversationId, java.time.LocalDateTime pendingCutoff) {
            return 0;
        }

        @Override
        public com.studyagent.service.domain.verla.VerlaAttachment softDeleteUserUpload(String clerkUserId, String objectId) {
            return null;
        }

        @Override
        public com.studyagent.service.domain.verla.VerlaAttachment updateParseProgress(com.studyagent.service.domain.verla.VerlaAttachment patch) {
            return patch;
        }

        @Override
        public com.studyagent.service.domain.verla.VerlaAttachment updateByObjectIdSelective(com.studyagent.service.domain.verla.VerlaAttachment patch) {
            return patch;
        }

        @Override
        public int markStaleUploadedAgentOutputsFailed(LocalDateTime cutoff, int batchSize, String reason) {
            return 0;
        }
    }

    private static class FakeTurnRepository implements VerlaTurnRepository {
        private final Map<Long, VerlaTurn> store = new HashMap<>();
        private long nextId = 1L;

        @Override
        public VerlaTurn save(VerlaTurn turn) {
            if (turn.getId() == null) {
                turn.setId(nextId++);
            }
            store.put(turn.getId(), copyTurn(turn));
            return turn;
        }

        @Override
        public VerlaTurn findById(Long id) {
            return copyTurn(store.get(id));
        }

        @Override
        public VerlaTurn findByIdForUpdate(Long id) {
            return findById(id);
        }

        @Override
        public List<VerlaTurn> findRecentByConversation(Long conversationId, int limit) {
            return store.values().stream()
                    .filter(turn -> conversationId.equals(turn.getConversationId()))
                    .sorted(Comparator.comparing(VerlaTurn::getId).reversed())
                    .limit(limit)
                    .map(VerlaTurnOrchestratorContextRefTest::copyTurn)
                    .toList();
        }

        @Override
        public List<VerlaTurn> findByIds(List<Long> turnIds) {
            return List.of();
        }

        @Override
        public Map<Long, List<VerlaTurn>> findRecentByConversationIds(List<Long> conversationIds) {
            return com.studyagent.service.application.verla.support.VerlaRepositoryTestDoubles
                    .batchRecentTurns(conversationIds, id -> findRecentByConversation(id, 20));
        }
    }

    private static class FakeSessionRepository implements VerlaSessionRepository {
        private final Map<Long, VerlaSession> store = new HashMap<>();
        private long nextId = 1L;

        @Override
        public VerlaSession save(VerlaSession session) {
            if (session.getId() == null) {
                session.setId(nextId++);
            }
            store.put(session.getId(), copySession(session));
            return session;
        }

        @Override
        public VerlaSession findById(Long id) {
            return copySession(store.get(id));
        }

        @Override
        public VerlaSession findByIdForUpdate(Long id) {
            return findById(id);
        }

        @Override
        public List<VerlaSession> findByTurn(Long turnId) {
            return store.values().stream()
                    .filter(session -> turnId.equals(session.getTurnId()))
                    .map(VerlaTurnOrchestratorContextRefTest::copySession)
                    .toList();
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

        @Override
        public int countActiveCapabilityRuns(String action) {
            return 0;
        }

        @Override
        public List<VerlaSession> findByIds(List<Long> sessionIds) {
            return List.of();
        }

        @Override
        public Map<Long, List<VerlaSession>> findByTurnIds(List<Long> turnIds) {
            return com.studyagent.service.application.verla.support.VerlaRepositoryTestDoubles
                    .batchSessionsByTurn(turnIds, this::findByTurn);
        }

        Long latestSessionId() {
            return store.keySet().stream().max(Long::compareTo).orElse(null);
        }
    }

    private static class FakeMessageRepository implements VerlaMessageRepository {
        private final Map<Long, VerlaMessage> store = new HashMap<>();
        private long nextId = 1L;

        @Override
        public VerlaMessage save(VerlaMessage message) {
            if (message.getId() == null) {
                message.setId(nextId++);
            }
            store.put(message.getId(), copyMessage(message));
            return message;
        }

        @Override
        public VerlaMessage findById(Long id) {
            return copyMessage(store.get(id));
        }

        @Override
        public List<VerlaMessage> findByCursor(Long conversationId, Long cursor, int limit) {
            List<VerlaMessage> rows = new ArrayList<>(store.values());
            rows.removeIf(message -> !conversationId.equals(message.getConversationId()));
            rows.sort(Comparator.comparing(VerlaMessage::getId).reversed());
            return rows.stream()
                    .filter(message -> cursor == null || message.getId() < cursor)
                    .limit(limit)
                    .map(VerlaTurnOrchestratorContextRefTest::copyMessage)
                    .toList();
        }

        @Override
        public List<VerlaMessage> findFileChatByCursor(Long conversationId, String objectId, Long cursor, int limit) {
            return List.of();
        }
    }

    private static class CapturingMqOutboxService extends MqOutboxService {
        private final List<VerlaCommandEnvelope> envelopes = new ArrayList<>();

        CapturingMqOutboxService() {
            super(null, null, new ObjectMapper());
        }

        @Override
        public MqOutbox createVerlaCommand(VerlaCommandEnvelope envelope, String exchange, String routingKey) {
            this.envelopes.add(envelope);
            return MqOutbox.builder().id(1L).build();
        }
    }
}
