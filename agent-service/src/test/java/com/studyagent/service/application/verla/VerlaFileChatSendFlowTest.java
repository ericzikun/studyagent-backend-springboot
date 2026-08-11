package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.payload.VerlaAttachmentParsedPayload;
import com.studyagent.service.application.verla.dto.FileChatPanelView;
import com.studyagent.service.application.verla.handler.VerlaAttachmentParsedEventHandler;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
import com.studyagent.service.application.MqOutboxService;
import com.studyagent.service.application.verla.entitlement.EntitlementService;
import com.studyagent.service.application.verla.dto.SendMessageResult;
import com.studyagent.service.application.verla.quota.VerlaQuotaService;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.verla.FollowupEditUsage;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
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

class VerlaFileChatSendFlowTest {

    private static final String USER_ID = "user_1";
    private static final Long CONVERSATION_ID = 1001L;

    private FakeConversationRepository conversationRepository;
    private FakeTurnRepository turnRepository;
    private FakeSessionRepository sessionRepository;
    private FakeMessageRepository messageRepository;
    private FakeAttachmentRepository attachmentRepository;
    private CapturingMqOutboxService mqOutboxService;
    private VerlaTurnOrchestrator orchestrator;
    private EntitlementService entitlementService;

    @BeforeEach
    void setUp() {
        conversationRepository = new FakeConversationRepository();
        turnRepository = new FakeTurnRepository();
        sessionRepository = new FakeSessionRepository();
        messageRepository = new FakeMessageRepository();
        attachmentRepository = new FakeAttachmentRepository();
        mqOutboxService = new CapturingMqOutboxService();
        entitlementService = Mockito.mock(EntitlementService.class);
        Mockito.when(entitlementService.reserveFollowupEdit(
                        Mockito.anyString(),
                        Mockito.anyLong(),
                        Mockito.anyLong(),
                        Mockito.anyList()))
                .thenAnswer(invocation -> FollowupEditUsage.builder()
                        .userMessageId(invocation.getArgument(2))
                        .build());

        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository,
                messageRepository,
                null);

        orchestrator = new VerlaTurnOrchestrator(
                conversationService,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                attachmentRepository,
                Mockito.mock(com.studyagent.service.domain.verla.repo.VerlaArtifactRepository.class),
                null,
                new TurnStateMachine(),
                new SessionStateMachine(),
                mqOutboxService,
                new ObjectMapper(),
                Mockito.mock(VerlaQuotaService.class),
                entitlementService,
                event -> {},
                Mockito.mock(com.studyagent.common.analytics.AnalyticsService.class),
                Mockito.mock(com.studyagent.service.application.verla.metrics.AssignmentBusinessMetrics.class));
        ReflectionTestUtils.setField(orchestrator, "commandExchange", "studyagent.command");

        conversationRepository.put(conversation(1L));
        attachmentRepository.byObjectId = VerlaAttachment.builder()
                .objectId("obj_123")
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .filename("calculus homework.pdf")
                .status("PARSED")
                .build();
    }

    @Test
    void startFileChat_shouldCreateTurnUserMessageSessionAndOutbox() {
        SendMessageResult result = orchestrator.startFileChat(
                USER_ID,
                CONVERSATION_ID,
                "obj_123",
                "Compare the 4 essay prompts for me.");

        assertThat(result.getTurnId()).isNotNull();
        assertThat(result.getUserMessageId()).isNotNull();
        assertThat(result.getAgentSessionId()).isNotNull();
        assertThat(result.getPlanSessionId()).isNull();

        VerlaMessage savedUserMessage = messageRepository.findById(result.getUserMessageId());
        assertThat(savedUserMessage.getConversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(savedUserMessage.getTurnId()).isEqualTo(result.getTurnId());
        assertThat(savedUserMessage.getRole()).isEqualTo("user");
        assertThat(savedUserMessage.getTextContent()).isEqualTo("Compare the 4 essay prompts for me.");
        assertThat(savedUserMessage.getMetaJson()).contains("\"scene\":\"FILE_CHAT\"");
        assertThat(savedUserMessage.getMetaJson()).contains("\"objectId\":\"obj_123\"");

        VerlaSession savedSession = sessionRepository.findById(result.getAgentSessionId());
        assertThat(savedSession.getKind()).isEqualTo("FILE_CHAT");
        assertThat(savedSession.getFeatureCode()).isEqualTo("FILE_CHAT");
        assertThat(savedSession.getStatus()).isEqualTo("DISPATCHING");

        VerlaTurn savedTurn = turnRepository.findById(result.getTurnId());
        assertThat(savedTurn.getStatus()).isEqualTo("RUNNING_AGENT");
        assertThat(savedTurn.getAgentSessionId()).isEqualTo(result.getAgentSessionId());
        assertThat(savedTurn.getActiveSessionId()).isEqualTo(result.getAgentSessionId());

        assertThat(mqOutboxService.lastEnvelope.getAction()).isEqualTo("cmd.file.chat");
        assertThat(mqOutboxService.lastEnvelope.getSession().getKind().name()).isEqualTo("FILE_CHAT");
        assertThat(mqOutboxService.lastEnvelope.getPayload().get("objectId")).isEqualTo("obj_123");
        assertThat(mqOutboxService.lastEnvelope.getPayload().get("message"))
                .isEqualTo("Compare the 4 essay prompts for me.");
        @SuppressWarnings("unchecked")
        Map<String, Object> contextRef = (Map<String, Object>) mqOutboxService.lastEnvelope.getPayload().get("contextRef");
        assertThat(contextRef.get("endpoint")).isEqualTo("/v1/internal/verla/sessions/" + result.getAgentSessionId() + "/context");
        assertThat(mqOutboxService.lastEnvelope.getPayload()).doesNotContainKeys("objectIds", "memory");
    }

    @Test
    void startFileChat_shouldBeVisibleInPanelQueryImmediately() {
        SendMessageResult result = orchestrator.startFileChat(
                USER_ID,
                CONVERSATION_ID,
                "obj_123",
                "Compare the 4 essay prompts for me.");

        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository,
                messageRepository,
                null);
        VerlaFileChatService fileChatService = new VerlaFileChatService(
                conversationService,
                attachmentRepository,
                messageRepository);

        assertThat(fileChatService.getPanel(USER_ID, CONVERSATION_ID, "obj_123", null, 20).getMessages())
                .extracting(
                        com.studyagent.service.application.verla.dto.FileChatPanelMessageView::getMessageId,
                        com.studyagent.service.application.verla.dto.FileChatPanelMessageView::getRole,
                        com.studyagent.service.application.verla.dto.FileChatPanelMessageView::getText)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        result.getUserMessageId(),
                        "user",
                        "Compare the 4 essay prompts for me."));
    }

    @Test
    void startFileChat_shouldNotBackfillLatestChatMemoryAnymore() {
        sessionRepository.save(VerlaSession.builder()
                .conversationId(CONVERSATION_ID)
                .turnId(98L)
                .kind("FILE_CHAT")
                .featureCode("FILE_CHAT")
                .status("SUCCEEDED")
                .resultJson("""
                        {"memory":{"chatFile":{"compressedSummary":"old-summary","messagesCountWhenCompressed":6,"updatedAt":"2026-05-21T01:49:15"}}}
                        """)
                .createdAt(LocalDateTime.now().minusMinutes(2))
                .updatedAt(LocalDateTime.now().minusMinutes(1))
                .build());

        SendMessageResult result = orchestrator.startFileChat(
                USER_ID,
                CONVERSATION_ID,
                "obj_123",
                "Continue from the previous round.");

        assertThat(result.getAgentSessionId()).isNotNull();
        assertThat(mqOutboxService.lastEnvelope.getPayload()).doesNotContainKey("memory");
    }

    @Test
    void onFileChatCompleted_shouldPersistAssistantMessageWithoutNeedingAttachmentMetadataFromCompletion() {
        SendMessageResult result = orchestrator.startFileChat(
                USER_ID,
                CONVERSATION_ID,
                "obj_123",
                "Compare the 4 essay prompts for me.");
        attachmentRepository.byObjectId = VerlaAttachment.builder()
                .objectId("obj_123")
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .filename("calculus homework.pdf")
                .status("PARSED")
                .metaJson("""
                        {
                          "fileChat": {
                            "analysisStatus": "READY",
                            "analysisText": "这是附件解析阶段就准备好的文件说明。",
                            "suggestedQuestions": [
                              "帮我比较四个 prompt 的差异",
                              "提取这个文件里的格式要求",
                              "我应该先选哪一道题"
                            ]
                          }
                        }
                        """)
                .build();

        orchestrator.onFileChatCompleted(result.getAgentSessionId(), Map.of(
                "objectId", "obj_123",
                "finalText", "这份文件更偏向题目说明，先比较四个 essay prompt 的要求会更有效。"));

        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository,
                messageRepository,
                null);
        VerlaFileChatService fileChatService = new VerlaFileChatService(
                conversationService,
                attachmentRepository,
                messageRepository);

        assertThat(fileChatService.getPanel(USER_ID, CONVERSATION_ID, "obj_123", null, 20).getMessages())
                .extracting(
                        com.studyagent.service.application.verla.dto.FileChatPanelMessageView::getRole,
                        com.studyagent.service.application.verla.dto.FileChatPanelMessageView::getText)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "assistant",
                                "这份文件更偏向题目说明，先比较四个 essay prompt 的要求会更有效。"),
                        org.assertj.core.groups.Tuple.tuple(
                                "user",
                                "Compare the 4 essay prompts for me."));
        assertThat(fileChatService.getPanel(USER_ID, CONVERSATION_ID, "obj_123", null, 20).getAnalysis().getText())
                .isEqualTo("这是附件解析阶段就准备好的文件说明。");
        assertThat(fileChatService.getPanel(USER_ID, CONVERSATION_ID, "obj_123", null, 20).getSuggestedQuestions())
                .containsExactly(
                        "帮我比较四个 prompt 的差异",
                        "提取这个文件里的格式要求",
                        "我应该先选哪一道题");
        assertThat(sessionRepository.findById(result.getAgentSessionId()).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(turnRepository.findById(result.getTurnId()).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void attachmentParsedThenFileChatLifecycle_shouldFollowLatestAlignedProtocol() {
        attachmentRepository.byObjectId = VerlaAttachment.builder()
                .objectId("obj_123")
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .filename("calculus homework.pdf")
                .status("PARSING")
                .build();
        VerlaAttachmentParsedEventHandler handler = new VerlaAttachmentParsedEventHandler(
                attachmentRepository,
                conversationRepository,
                new ObjectMapper());
        VerlaAttachmentParsedPayload payload = VerlaAttachmentParsedPayload.builder()
                .objectId("obj_123")
                .status("PARSED")
                .summary("这是附件解析阶段就准备好的文件说明。")
                .suggestedQuestions(List.of(
                        "帮我提取这个文件里的格式要求",
                        "这个文件和当前作业的关系是什么",
                        "我应该先从哪一步开始"))
                .progress(100)
                .build();

        handler.handle(
                VerlaEventInbox.builder()
                        .conversationId(CONVERSATION_ID)
                        .turnId(77L)
                        .sessionId(88L)
                        .eventSeq(1L)
                        .build(),
                VerlaEventEnvelope.builder()
                        .eventType("ATTACHMENT_PARSED")
                        .payload(new ObjectMapper().convertValue(payload, Map.class))
                        .build());

        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository,
                messageRepository,
                null);
        VerlaFileChatService fileChatService = new VerlaFileChatService(
                conversationService,
                attachmentRepository,
                messageRepository);

        FileChatPanelView initialPanel = fileChatService.getPanel(USER_ID, CONVERSATION_ID, "obj_123", null, 20);
        assertThat(initialPanel.getAnalysis().getText()).isEqualTo("这是附件解析阶段就准备好的文件说明。");
        assertThat(initialPanel.getSuggestedQuestions()).containsExactly(
                "帮我提取这个文件里的格式要求",
                "这个文件和当前作业的关系是什么",
                "我应该先从哪一步开始");
        assertThat(initialPanel.getMessages()).isEmpty();

        SendMessageResult result = orchestrator.startFileChat(
                USER_ID,
                CONVERSATION_ID,
                "obj_123",
                "先帮我提取格式要求。");
        assertThat(mqOutboxService.lastEnvelope.getPayload())
                .containsOnlyKeys("objectId", "message", "userMessageId", "contextRef");
        assertThat(mqOutboxService.lastEnvelope.getPayload().get("userMessageId"))
                .isEqualTo(result.getUserMessageId());

        orchestrator.onFileChatCompleted(result.getAgentSessionId(), Map.of(
                "objectId", "obj_123",
                "finalText", "这份文件要求先明确页数、格式和结构，再开始写作。"));

        FileChatPanelView completedPanel = fileChatService.getPanel(USER_ID, CONVERSATION_ID, "obj_123", null, 20);
        assertThat(completedPanel.getAnalysis().getText()).isEqualTo("这是附件解析阶段就准备好的文件说明。");
        assertThat(completedPanel.getSuggestedQuestions()).containsExactly(
                "帮我提取这个文件里的格式要求",
                "这个文件和当前作业的关系是什么",
                "我应该先从哪一步开始");
        assertThat(completedPanel.getMessages())
                .extracting(
                        com.studyagent.service.application.verla.dto.FileChatPanelMessageView::getRole,
                        com.studyagent.service.application.verla.dto.FileChatPanelMessageView::getText)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("assistant", "这份文件要求先明确页数、格式和结构，再开始写作。"),
                        org.assertj.core.groups.Tuple.tuple("user", "先帮我提取格式要求。"));
    }

    @Test
    void onFileChatFailed_shouldOnlyFailFileChatTurnAndSession() {
        SendMessageResult result = orchestrator.startFileChat(
                USER_ID,
                CONVERSATION_ID,
                "obj_123",
                "Compare the 4 essay prompts for me.");

        orchestrator.onFileChatFailed(result.getAgentSessionId(), Map.of(
                "objectId", "obj_123",
                "errorMessage", "mock failure"));

        assertThat(sessionRepository.findById(result.getAgentSessionId()).getStatus()).isEqualTo("FAILED");
        assertThat(turnRepository.findById(result.getTurnId()).getStatus()).isEqualTo("FAILED");
        assertThat(conversationRepository.findById(CONVERSATION_ID).getStatus()).isEqualTo("active");
        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository,
                messageRepository,
                null);
        VerlaFileChatService fileChatService = new VerlaFileChatService(
                conversationService,
                attachmentRepository,
                messageRepository);
        assertThat(fileChatService.getPanel(USER_ID, CONVERSATION_ID, "obj_123", null, 20).getMessages())
                .extracting(com.studyagent.service.application.verla.dto.FileChatPanelMessageView::getRole)
                .containsExactly("user");
    }

    @Test
    void onFileChatCancelled_shouldConfirmCancellingTurn() {
        SendMessageResult result = orchestrator.startFileChat(
                USER_ID,
                CONVERSATION_ID,
                "obj_123",
                "Compare the 4 essay prompts for me.");

        VerlaTurn turn = turnRepository.findById(result.getTurnId());
        turn.setStatus("CANCELLING");
        turnRepository.save(turn);
        VerlaSession session = sessionRepository.findById(result.getAgentSessionId());
        session.setStatus("CANCELLING");
        sessionRepository.save(session);

        orchestrator.onFileChatCancelled(result.getAgentSessionId());

        assertThat(sessionRepository.findById(result.getAgentSessionId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(turnRepository.findById(result.getTurnId()).getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelAssignmentChat_shouldMarkTurnAndSessionCancellingAndDispatchControlCommand() {
        SendMessageResult result = orchestrator.startAssignmentChat(
                USER_ID,
                CONVERSATION_ID,
                "帮我修改作业正文。",
                List.of("art_md"));

        orchestrator.cancelAssignmentChat(USER_ID, CONVERSATION_ID, result.getAgentSessionId());

        assertThat(sessionRepository.findById(result.getAgentSessionId()).getStatus()).isEqualTo("CANCELLING");
        assertThat(turnRepository.findById(result.getTurnId()).getStatus()).isEqualTo("CANCELLING");
        assertThat(mqOutboxService.lastEnvelope.getAction()).isEqualTo("cmd.assignment.chat.control.cancel");
        assertThat(mqOutboxService.lastEnvelope.getPayload().get("sessionId"))
                .isEqualTo(result.getAgentSessionId());
    }

    @Test
    void retryAssignmentChat_shouldReuseOriginalMessageAndArtifactScopeWithoutDuplicatingUserMessage() {
        SendMessageResult first = orchestrator.startAssignmentChat(
                USER_ID,
                CONVERSATION_ID,
                "帮我修改作业正文。",
                List.of("art_md", "art_code"));
        VerlaTurn failedTurn = turnRepository.findById(first.getTurnId());
        failedTurn.setStatus("FAILED");
        turnRepository.save(failedTurn);
        VerlaSession failedSession = sessionRepository.findById(first.getAgentSessionId());
        failedSession.setStatus("FAILED");
        sessionRepository.save(failedSession);

        SendMessageResult retry = orchestrator.retryAssignmentChat(USER_ID, CONVERSATION_ID, first.getTurnId());

        assertThat(retry.getTurnId()).isEqualTo(first.getTurnId());
        assertThat(retry.getUserMessageId()).isEqualTo(first.getUserMessageId());
        assertThat(retry.getAgentSessionId()).isNotEqualTo(first.getAgentSessionId());
        assertThat(messageRepository.findById(first.getUserMessageId()).getTextContent())
                .isEqualTo("帮我修改作业正文。");
        assertThat(turnRepository.findById(first.getTurnId()).getStatus()).isEqualTo("RUNNING_AGENT");
        assertThat(turnRepository.findById(first.getTurnId()).getAgentSessionId())
                .isEqualTo(retry.getAgentSessionId());
        assertThat(mqOutboxService.lastEnvelope.getAction()).isEqualTo("cmd.assignment.chat");
        assertThat(mqOutboxService.lastEnvelope.getPayload().get("message"))
                .isEqualTo("帮我修改作业正文。");
        assertThat(mqOutboxService.lastEnvelope.getPayload().get("artifactUids"))
                .isEqualTo(List.of("art_md", "art_code"));
    }

    private static VerlaConversation conversation(Long version) {
        return VerlaConversation.builder()
                .id(CONVERSATION_ID)
                .userId(USER_ID)
                .title("作业辅导")
                .status("active")
                .primaryIntent("ASSIGNMENT")
                .workspaceJson("{}")
                .turnCount(0)
                .version(version)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
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

    private static final class FakeConversationRepository implements VerlaConversationRepository {
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
        public List<VerlaConversation> findByUserFilteredPaged(String userId, String segmentQueryKey, String conversationStatusDb, int page, int size) {
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

    private static final class FakeTurnRepository implements VerlaTurnRepository {
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
                    .map(VerlaFileChatSendFlowTest::copyTurn)
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

    private static final class FakeSessionRepository implements VerlaSessionRepository {
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
                    .map(VerlaFileChatSendFlowTest::copySession)
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

    }

    private static final class FakeMessageRepository implements VerlaMessageRepository {
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
            return List.of();
        }

        @Override
        public List<VerlaMessage> findFileChatByCursor(Long conversationId, String objectId, Long cursor, int limit) {
            return store.values().stream()
                    .filter(message -> conversationId.equals(message.getConversationId()))
                    .filter(message -> cursor == null || message.getId() < cursor)
                    .filter(message -> {
                        com.studyagent.service.application.verla.dto.FileChatMessageMeta meta =
                                VerlaFileChatMetadataHelper.readMessageMeta(message.getMetaJson());
                        if (meta == null) {
                            return false;
                        }
                        return com.studyagent.service.application.verla.dto.FileChatMessageMeta.SCENE_FILE_CHAT.equals(meta.getScene())
                                && objectId.equals(meta.getObjectId());
                    })
                    .sorted(Comparator.comparing(VerlaMessage::getId).reversed())
                    .limit(limit)
                    .map(VerlaFileChatSendFlowTest::copyMessage)
                    .toList();
        }
    }

    private static final class FakeAttachmentRepository implements VerlaAttachmentRepository {
        private VerlaAttachment byObjectId;

        @Override
        public VerlaAttachment save(VerlaAttachment attachment) {
            byObjectId = attachment;
            return attachment;
        }

        @Override
        public VerlaAttachment findById(Long id) {
            return null;
        }

        @Override
        public VerlaAttachment findByObjectId(String objectId) {
            return byObjectId != null && objectId.equals(byObjectId.getObjectId()) ? byObjectId : null;
        }

        @Override
        public List<VerlaAttachment> findByObjectIds(List<String> objectIds) {
            return List.of();
        }

        @Override
        public List<VerlaAttachment> listByConversation(Long conversationId, int limit) {
            return List.of();
        }

        @Override
        public List<VerlaAttachment> listByTurn(Long turnId) {
            return List.of();
        }

        @Override
        public long countActiveUserUploadsForConversation(Long conversationId, LocalDateTime pendingCutoff) {
            return 0;
        }

        @Override
        public VerlaAttachment softDeleteUserUpload(String clerkUserId, String objectId) {
            return byObjectId;
        }

        @Override
        public VerlaAttachment updateParseProgress(VerlaAttachment patch) {
            VerlaAttachment current = byObjectId;
            if (current == null || patch == null || patch.getObjectId() == null
                    || !patch.getObjectId().equals(current.getObjectId())) {
                byObjectId = patch;
                return patch;
            }
            if (patch.getStatus() != null) {
                current.setStatus(patch.getStatus());
            }
            current.setParseProgress(patch.getParseProgress());
            current.setParseError(patch.getParseError());
            if (patch.getSummary() != null) {
                current.setSummary(patch.getSummary());
            }
            if (patch.getPrimaryArtifactUid() != null) {
                current.setPrimaryArtifactUid(patch.getPrimaryArtifactUid());
            }
            if (patch.getMetaJson() != null) {
                current.setMetaJson(patch.getMetaJson());
            }
            if (patch.getMarkdownContent() != null) {
                current.setMarkdownContent(patch.getMarkdownContent());
            }
            if (patch.getImagesJson() != null) {
                current.setImagesJson(patch.getImagesJson());
            }
            byObjectId = current;
            return current;
        }

        @Override
        public VerlaAttachment updateByObjectIdSelective(VerlaAttachment patch) {
            if (byObjectId != null && patch != null && patch.getObjectId() != null
                    && patch.getObjectId().equals(byObjectId.getObjectId())) {
                if (patch.getMetaJson() != null) {
                    byObjectId.setMetaJson(patch.getMetaJson());
                }
                if (patch.getStatus() != null) {
                    byObjectId.setStatus(patch.getStatus());
                }
                if (patch.getParseError() != null) {
                    byObjectId.setParseError(patch.getParseError());
                }
                return byObjectId;
            }
            return patch;
        }

        @Override
        public int markStaleUploadedAgentOutputsFailed(LocalDateTime cutoff, int batchSize, String reason) {
            return 0;
        }
    }

    private static final class CapturingMqOutboxService extends MqOutboxService {
        private VerlaCommandEnvelope lastEnvelope;

        CapturingMqOutboxService() {
            super(null, null, new ObjectMapper());
        }

        @Override
        public MqOutbox createVerlaCommand(VerlaCommandEnvelope envelope, String exchange, String routingKey) {
            this.lastEnvelope = envelope;
            return MqOutbox.builder().id(1L).build();
        }
    }
}
