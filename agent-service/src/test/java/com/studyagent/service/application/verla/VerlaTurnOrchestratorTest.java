package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.studyagent.common.analytics.AnalyticsEvents;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.enums.VerlaSessionKind;
import com.studyagent.service.application.MqOutboxService;
import com.studyagent.service.application.verla.entitlement.EffectiveEntitlements;
import com.studyagent.service.application.verla.entitlement.EntitlementService;
import com.studyagent.service.application.verla.quota.VerlaQuotaConsumeResult;
import com.studyagent.service.application.verla.quota.VerlaQuotaContext;
import com.studyagent.service.application.verla.quota.VerlaQuotaService;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.mq.MqOutboxRepository;
import com.studyagent.service.domain.verla.FollowupEditUsage;
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
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerlaTurnOrchestratorTest {

    private static final String AGENT_WORKFORCE_COMPLETED_TEXT = "Verla agent team task finished";

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
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                null,
                new ObjectMapper(),
                new NoopQuotaService(),
                mockEntitlementService(),
                event -> {},
                mockAnalyticsService());

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

        assertEquals(2, messageRepository.savedMessages.size());
        VerlaMessage workforceStatus = messageRepository.savedMessages.get(0);
        assertEquals("agent_workforce", workforceStatus.getRole());
        assertEquals(AGENT_WORKFORCE_COMPLETED_TEXT, workforceStatus.getTextContent());
        VerlaMessage savedMessage = messageRepository.savedMessages.get(1);
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
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                null,
                new ObjectMapper(),
                new NoopQuotaService(),
                mockEntitlementService(),
                event -> {},
                mockAnalyticsService());

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

        assertEquals(2, messageRepository.savedMessages.size());
        assertEquals("agent_workforce", messageRepository.savedMessages.get(0).getRole());
        assertEquals(AGENT_WORKFORCE_COMPLETED_TEXT, messageRepository.savedMessages.get(0).getTextContent());
        assertEquals("system", messageRepository.savedMessages.get(1).getRole());
        assertEquals("Done", messageRepository.savedMessages.get(1).getTextContent());
    }

    @Test
    void onAgentCompleted_does_not_duplicate_workforce_status_on_replay() {
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
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                null,
                new ObjectMapper(),
                new NoopQuotaService(),
                mockEntitlementService(),
                event -> {},
                mockAnalyticsService());

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

        Map<String, Object> payload = Map.of("finalResult", "Done");
        orchestrator.onAgentCompleted(357L, payload);
        orchestrator.onAgentCompleted(357L, payload);

        assertEquals(2, messageRepository.savedMessages.size());
        assertEquals(1, messageRepository.savedMessages.stream()
                .filter(message -> "agent_workforce".equals(message.getRole()))
                .count());
        assertEquals(AGENT_WORKFORCE_COMPLETED_TEXT, messageRepository.savedMessages.get(0).getTextContent());
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
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                null,
                new ObjectMapper(),
                new NoopQuotaService(),
                mockEntitlementService(),
                event -> {},
                mockAnalyticsService());

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
    void onAgentFailed_preserves_agentWorkforce_role_from_payload() {
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
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                null,
                new ObjectMapper(),
                new NoopQuotaService(),
                mockEntitlementService(),
                event -> {},
                mockAnalyticsService());

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
                "role", "agent_workforce",
                "errorMessage", "Run failed"));

        assertNotNull(messageRepository.saved);
        assertEquals("agent_workforce", messageRepository.saved.getRole());
        assertEquals("Run failed", messageRepository.saved.getTextContent());
    }

    @Test
    void onAssignmentCompleted_captures_generation_success_with_conversation_user() {
        FakeSessionRepository sessionRepository = new FakeSessionRepository();
        FakeTurnRepository turnRepository = new FakeTurnRepository();
        FakeMessageRepository messageRepository = new FakeMessageRepository();
        FakeConversationRepository conversationRepository = new FakeConversationRepository();
        AnalyticsService analyticsService = Mockito.mock(AnalyticsService.class);
        VerlaTurnOrchestrator orchestrator = new VerlaTurnOrchestrator(
                null,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                new NoopAttachmentRepository(),
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                null,
                new ObjectMapper(),
                new NoopQuotaService(),
                mockEntitlementService(),
                event -> {},
                analyticsService);

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
        conversationRepository.conversation = VerlaConversation.builder()
                .id(153L)
                .userId("user_153")
                .build();

        orchestrator.onAssignmentCompleted(357L, Map.of("finalResult", "Done"));

        Mockito.verify(analyticsService).capture(
                "user_153",
                AnalyticsEvents.ASSIGNMENT_GENERATION_SUCCEEDED,
                Map.of("conversation_id", 153L, "task_type", "assignment"));
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
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                mqOutboxService,
                objectMapper,
                new NoopQuotaService(),
                mockEntitlementService(),
                event -> {},
                mockAnalyticsService());

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

    @Test
    void finalizeAssignmentClarify_rejectsUnsupportedPptBeforeQuotaConsume() {
        FakeSessionRepository sessionRepository = new FakeSessionRepository();
        FakeTurnRepository turnRepository = new FakeTurnRepository();
        FakeMessageRepository messageRepository = new FakeMessageRepository();
        FakeConversationRepository conversationRepository = new FakeConversationRepository();
        FakeMqOutboxRepository mqOutboxRepository = new FakeMqOutboxRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MqOutboxService mqOutboxService = new MqOutboxService(mqOutboxRepository, event -> { }, objectMapper);
        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository, messageRepository, new ConversationStateMachine());
        VerlaQuotaService quotaService = Mockito.mock(VerlaQuotaService.class);
        EntitlementService entitlementService = Mockito.mock(EntitlementService.class);
        Mockito.when(entitlementService.getEffectiveEntitlements("free_user"))
                .thenReturn(new EffectiveEntitlements("free", "free", 3, 3, Set.of("writing")));
        Mockito.doThrow(new BusinessException(ApiCode.OUTPUT_TYPE_NOT_ALLOWED))
                .when(entitlementService)
                .assertAssignmentOutputAllowed(Mockito.any(EffectiveEntitlements.class), Mockito.anyMap());
        VerlaTurnOrchestrator orchestrator = new VerlaTurnOrchestrator(
                conversationService,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                new NoopAttachmentRepository(),
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                mqOutboxService,
                objectMapper,
                quotaService,
                entitlementService,
                event -> {},
                mockAnalyticsService());

        conversationRepository.conversation = VerlaConversation.builder()
                .id(74L)
                .userId("free_user")
                .status(ConversationStatus.ACTIVE.getDbValue())
                .build();
        turnRepository.turn = VerlaTurn.builder()
                .id(700L)
                .conversationId(74L)
                .userMessageId(901L)
                .resolvedIntent("ASSIGNMENT")
                .status(TurnStatus.PLANNING.name())
                .build();

        assertThrows(BusinessException.class,
                () -> orchestrator.finalizeAssignmentClarify(
                        "free_user",
                        74L,
                        null,
                        Map.of(),
                        List.of(),
                        Map.of("deliverable_count", Map.of("markdown", 0, "ppt", 1, "code", 0)),
                        List.of()));
        Mockito.verifyNoInteractions(quotaService);
    }

    @Test
    void startAssignmentRunFromFinalClarify_rechecksEntitlementBeforeDispatch() throws Exception {
        FakeSessionRepository sessionRepository = new FakeSessionRepository();
        FakeTurnRepository turnRepository = new FakeTurnRepository();
        FakeMessageRepository messageRepository = new FakeMessageRepository();
        FakeConversationRepository conversationRepository = new FakeConversationRepository();
        FakeMqOutboxRepository mqOutboxRepository = new FakeMqOutboxRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MqOutboxService mqOutboxService = new MqOutboxService(mqOutboxRepository, event -> { }, objectMapper);
        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository, messageRepository, new ConversationStateMachine());
        VerlaQuotaService quotaService = Mockito.mock(VerlaQuotaService.class);
        EntitlementService entitlementService = Mockito.mock(EntitlementService.class);
        Mockito.when(entitlementService.getEffectiveEntitlements("free_user"))
                .thenReturn(new EffectiveEntitlements("free", "free", 3, 3, Set.of("writing")));
        Mockito.doThrow(new BusinessException(ApiCode.OUTPUT_TYPE_NOT_ALLOWED))
                .when(entitlementService)
                .assertAssignmentOutputAllowed(Mockito.any(EffectiveEntitlements.class), Mockito.anyMap());
        VerlaTurnOrchestrator orchestrator = new VerlaTurnOrchestrator(
                conversationService,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                new NoopAttachmentRepository(),
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                mqOutboxService,
                objectMapper,
                quotaService,
                entitlementService,
                event -> {},
                mockAnalyticsService());

        conversationRepository.conversation = VerlaConversation.builder()
                .id(74L)
                .userId("free_user")
                .status(ConversationStatus.ACTIVE.getDbValue())
                .build();
        turnRepository.turn = VerlaTurn.builder()
                .id(700L)
                .conversationId(74L)
                .userMessageId(901L)
                .resolvedIntent("ASSIGNMENT")
                .status(TurnStatus.COMPLETED.name())
                .build();
        sessionRepository.sessionsByTurn = List.of(
                VerlaSession.builder()
                        .id(800L)
                        .turnId(700L)
                        .kind(VerlaSessionKind.ASSIGNMENT.name())
                        .resultJson(objectMapper.writeValueAsString(Map.of(
                                "isReadyForGeneration", true,
                                "requirementForm", Map.of(
                                        "deliverable_count", Map.of("markdown", 0, "ppt", 1, "code", 0)))))
                        .build());

        assertThrows(BusinessException.class,
                () -> orchestrator.startAssignmentRunFromFinalClarify("free_user", 74L));
        Mockito.verifyNoInteractions(quotaService);
    }

    @Test
    void startAssignmentRunFromFinalClarify_infersSlidesDeliverableBeforeEntitlementCheck() throws Exception {
        FakeSessionRepository sessionRepository = new FakeSessionRepository();
        FakeTurnRepository turnRepository = new FakeTurnRepository();
        FakeMessageRepository messageRepository = new FakeMessageRepository();
        FakeConversationRepository conversationRepository = new FakeConversationRepository();
        FakeMqOutboxRepository mqOutboxRepository = new FakeMqOutboxRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MqOutboxService mqOutboxService = new MqOutboxService(mqOutboxRepository, event -> { }, objectMapper);
        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository, messageRepository, new ConversationStateMachine());
        VerlaQuotaService quotaService = Mockito.mock(VerlaQuotaService.class);
        EntitlementService entitlementService = Mockito.mock(EntitlementService.class);
        Mockito.when(entitlementService.getEffectiveEntitlements("free_user"))
                .thenReturn(new EffectiveEntitlements("free", "free", 3, 3, Set.of("writing")));
        Mockito.doAnswer(invocation -> {
            Map<String, Object> requirementForm = invocation.getArgument(1);
            Object deliverableCountRaw = requirementForm.get("deliverable_count");
            Map<String, Object> deliverableCount = deliverableCountRaw instanceof Map<?, ?> rawMap
                    ? rawMap.entrySet().stream()
                    .filter(entry -> entry.getKey() != null)
                    .collect(Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()),
                            Map.Entry::getValue))
                    : Map.of();
            Number ppt = deliverableCount.get("ppt") instanceof Number number ? number : Integer.valueOf(0);
            if (ppt.intValue() > 0) {
                throw new BusinessException(ApiCode.OUTPUT_TYPE_NOT_ALLOWED);
            }
            return null;
        }).when(entitlementService).assertAssignmentOutputAllowed(Mockito.any(EffectiveEntitlements.class), Mockito.anyMap());
        VerlaTurnOrchestrator orchestrator = new VerlaTurnOrchestrator(
                conversationService,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                new NoopAttachmentRepository(),
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                mqOutboxService,
                objectMapper,
                quotaService,
                entitlementService,
                event -> {},
                mockAnalyticsService());

        conversationRepository.conversation = VerlaConversation.builder()
                .id(74L)
                .userId("free_user")
                .status(ConversationStatus.ACTIVE.getDbValue())
                .build();
        turnRepository.turn = VerlaTurn.builder()
                .id(700L)
                .conversationId(74L)
                .userMessageId(901L)
                .resolvedIntent("ASSIGNMENT")
                .status(TurnStatus.COMPLETED.name())
                .build();
        sessionRepository.sessionsByTurn = List.of(
                VerlaSession.builder()
                        .id(800L)
                        .turnId(700L)
                        .kind(VerlaSessionKind.ASSIGNMENT.name())
                        .resultJson(objectMapper.writeValueAsString(Map.of(
                                "isReadyForGeneration", true,
                                "requirementForm", Map.of(
                                        "task_title", "Build a presentation deck for the client pitch"))))
                        .build());

        assertThrows(BusinessException.class,
                () -> orchestrator.startAssignmentRunFromFinalClarify("free_user", 74L));
        Mockito.verifyNoInteractions(quotaService);
    }

    @Test
    void startAssignmentRunFromFinalClarify_passesAllowedOutputTypesToPythonPayload() throws Exception {
        FakeSessionRepository sessionRepository = new FakeSessionRepository();
        FakeTurnRepository turnRepository = new FakeTurnRepository();
        FakeMessageRepository messageRepository = new FakeMessageRepository();
        FakeConversationRepository conversationRepository = new FakeConversationRepository();
        FakeMqOutboxRepository mqOutboxRepository = new FakeMqOutboxRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MqOutboxService mqOutboxService = new MqOutboxService(mqOutboxRepository, event -> { }, objectMapper);
        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository, messageRepository, new ConversationStateMachine());
        VerlaQuotaService quotaService = Mockito.mock(VerlaQuotaService.class);
        EntitlementService entitlementService = Mockito.mock(EntitlementService.class);
        Mockito.when(entitlementService.getEffectiveEntitlements("free_user"))
                .thenReturn(new EffectiveEntitlements("free", "free", 3, 3, Set.of("writing")));
        VerlaTurnOrchestrator orchestrator = new VerlaTurnOrchestrator(
                conversationService,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                new NoopAttachmentRepository(),
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                mqOutboxService,
                objectMapper,
                quotaService,
                entitlementService,
                event -> {},
                mockAnalyticsService());

        conversationRepository.conversation = VerlaConversation.builder()
                .id(74L)
                .userId("free_user")
                .status(ConversationStatus.ACTIVE.getDbValue())
                .build();
        turnRepository.turn = VerlaTurn.builder()
                .id(700L)
                .conversationId(74L)
                .userMessageId(901L)
                .resolvedIntent("ASSIGNMENT")
                .status(TurnStatus.COMPLETED.name())
                .build();
        sessionRepository.sessionsByTurn = List.of(
                VerlaSession.builder()
                        .id(800L)
                        .turnId(700L)
                        .kind(VerlaSessionKind.ASSIGNMENT.name())
                        .resultJson(objectMapper.writeValueAsString(Map.of(
                                "isReadyForGeneration", true,
                                "requirementForm", Map.of(
                                        "deliverable_count", Map.of("markdown", 1, "ppt", 0, "code", 0)))))
                        .build());

        orchestrator.startAssignmentRunFromFinalClarify("free_user", 74L);

        MqOutbox runCommand = mqOutboxRepository.findSavedByAction("cmd.assignment.run");
        Map<String, Object> envelope = objectMapper.readValue(runCommand.getPayload(), Map.class);
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertEquals(List.of("writing"), payload.get("allowedOutputTypes"));
    }

    @Test
    void finalizeAssignmentClarify_passesAllowedOutputTypesToPythonPayload() throws Exception {
        FakeSessionRepository sessionRepository = new FakeSessionRepository();
        FakeTurnRepository turnRepository = new FakeTurnRepository();
        FakeMessageRepository messageRepository = new FakeMessageRepository();
        FakeConversationRepository conversationRepository = new FakeConversationRepository();
        FakeMqOutboxRepository mqOutboxRepository = new FakeMqOutboxRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MqOutboxService mqOutboxService = new MqOutboxService(mqOutboxRepository, event -> { }, objectMapper);
        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository, messageRepository, new ConversationStateMachine());
        VerlaQuotaService quotaService = Mockito.mock(VerlaQuotaService.class);
        EntitlementService entitlementService = Mockito.mock(EntitlementService.class);
        Mockito.when(entitlementService.getEffectiveEntitlements("free_user"))
                .thenReturn(new EffectiveEntitlements("free", "free", 3, 3, Set.of("writing")));
        VerlaTurnOrchestrator orchestrator = new VerlaTurnOrchestrator(
                conversationService,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                new NoopAttachmentRepository(),
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                mqOutboxService,
                objectMapper,
                quotaService,
                entitlementService,
                event -> {},
                mockAnalyticsService());

        conversationRepository.conversation = VerlaConversation.builder()
                .id(74L)
                .userId("free_user")
                .status(ConversationStatus.ACTIVE.getDbValue())
                .build();
        turnRepository.turn = VerlaTurn.builder()
                .id(700L)
                .conversationId(74L)
                .userMessageId(901L)
                .resolvedIntent("ASSIGNMENT")
                .status(TurnStatus.PLANNING.name())
                .build();

        orchestrator.finalizeAssignmentClarify(
                "free_user",
                74L,
                null,
                Map.of(),
                List.of(),
                Map.of("deliverable_count", Map.of("markdown", 1, "ppt", 0, "code", 0)),
                List.of());

        MqOutbox clarifyCommand = mqOutboxRepository.findSavedByAction("cmd.assignment.clarify");
        Map<String, Object> envelope = objectMapper.readValue(clarifyCommand.getPayload(), Map.class);
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertEquals(List.of("writing"), payload.get("allowedOutputTypes"));
    }

    @Test
    void startAssignmentChat_rejectsWhenFollowupLimitReached() {
        FakeSessionRepository sessionRepository = new FakeSessionRepository();
        FakeTurnRepository turnRepository = new FakeTurnRepository();
        FakeMessageRepository messageRepository = new FakeMessageRepository();
        FakeConversationRepository conversationRepository = new FakeConversationRepository();
        FakeMqOutboxRepository mqOutboxRepository = new FakeMqOutboxRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MqOutboxService mqOutboxService = new MqOutboxService(mqOutboxRepository, event -> { }, objectMapper);
        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository, messageRepository, new ConversationStateMachine());
        EntitlementService entitlementService = Mockito.mock(EntitlementService.class);
        Mockito.doThrow(new BusinessException(ApiCode.FOLLOWUP_EDIT_LIMIT_REACHED))
                .when(entitlementService)
                .reserveFollowupEdit(Mockito.eq("user_1"), Mockito.eq(74L), Mockito.anyLong(), Mockito.anyList());
        VerlaTurnOrchestrator orchestrator = new VerlaTurnOrchestrator(
                conversationService,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                new NoopAttachmentRepository(),
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                mqOutboxService,
                objectMapper,
                new NoopQuotaService(),
                entitlementService,
                event -> {},
                mockAnalyticsService());

        conversationRepository.conversation = VerlaConversation.builder()
                .id(74L)
                .userId("user_1")
                .status(ConversationStatus.ACTIVE.getDbValue())
                .build();

        assertThrows(BusinessException.class,
                () -> orchestrator.startAssignmentChat("user_1", 74L, "edit this", List.of("art_1")));
    }

    @Test
    void retryAssignmentChat_reusesSameUsageRowForSameUserMessageId() {
        FakeSessionRepository sessionRepository = new FakeSessionRepository();
        FakeTurnRepository turnRepository = new FakeTurnRepository();
        FakeMessageRepository messageRepository = new FakeMessageRepository();
        FakeConversationRepository conversationRepository = new FakeConversationRepository();
        FakeMqOutboxRepository mqOutboxRepository = new FakeMqOutboxRepository();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MqOutboxService mqOutboxService = new MqOutboxService(mqOutboxRepository, event -> { }, objectMapper);
        VerlaConversationService conversationService = new VerlaConversationService(
                conversationRepository, messageRepository, new ConversationStateMachine());
        EntitlementService entitlementService = Mockito.mock(EntitlementService.class);
        Mockito.when(entitlementService.reserveFollowupEdit("user_1", 74L, 901L, List.of("art_1")))
                .thenReturn(FollowupEditUsage.builder().userMessageId(901L).build());
        VerlaTurnOrchestrator orchestrator = new VerlaTurnOrchestrator(
                conversationService,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                new NoopAttachmentRepository(),
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                mqOutboxService,
                objectMapper,
                new NoopQuotaService(),
                entitlementService,
                event -> {},
                mockAnalyticsService());

        conversationRepository.conversation = VerlaConversation.builder()
                .id(74L)
                .userId("user_1")
                .status(ConversationStatus.ACTIVE.getDbValue())
                .build();
        turnRepository.turn = VerlaTurn.builder()
                .id(801L)
                .conversationId(74L)
                .userMessageId(901L)
                .resolvedSlotsJson("{\"artifactUids\":[\"art_1\"]}")
                .status(TurnStatus.COMPLETED.name())
                .build();
        messageRepository.saved = VerlaMessage.builder()
                .id(901L)
                .conversationId(74L)
                .textContent("edit this")
                .build();

        orchestrator.retryAssignmentChat("user_1", 74L, 801L);

        Mockito.verify(entitlementService).reserveFollowupEdit("user_1", 74L, 901L, List.of("art_1"));
        Mockito.verify(entitlementService).bindFollowupEditSession(901L, 1001L);
    }

    @Test
    void onAssignmentChatFailed_releasesReservedFollowupUsage() {
        FakeSessionRepository sessionRepository = new FakeSessionRepository();
        FakeTurnRepository turnRepository = new FakeTurnRepository();
        FakeMessageRepository messageRepository = new FakeMessageRepository();
        FakeConversationRepository conversationRepository = new FakeConversationRepository();
        EntitlementService entitlementService = Mockito.mock(EntitlementService.class);
        VerlaTurnOrchestrator orchestrator = new VerlaTurnOrchestrator(
                null,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                new NoopAttachmentRepository(),
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                null,
                new ObjectMapper(),
                new NoopQuotaService(),
                entitlementService,
                event -> {},
                mockAnalyticsService());

        sessionRepository.session = VerlaSession.builder()
                .id(700L)
                .conversationId(74L)
                .turnId(801L)
                .status(SessionStatus.RUNNING.name())
                .kind(VerlaSessionKind.ASSIGNMENT_CHAT.name())
                .build();
        turnRepository.turn = VerlaTurn.builder()
                .id(801L)
                .conversationId(74L)
                .status(TurnStatus.RUNNING_AGENT.name())
                .build();

        orchestrator.onAssignmentChatFailed(700L, Map.of("errorMessage", "failed"));

        Mockito.verify(entitlementService).releaseFollowupEdit(700L, "assignment_chat_failed");
    }

    @Test
    void onAssignmentChatCompleted_marksUsageCompleted() {
        FakeSessionRepository sessionRepository = new FakeSessionRepository();
        FakeTurnRepository turnRepository = new FakeTurnRepository();
        FakeMessageRepository messageRepository = new FakeMessageRepository();
        FakeConversationRepository conversationRepository = new FakeConversationRepository();
        EntitlementService entitlementService = Mockito.mock(EntitlementService.class);
        VerlaTurnOrchestrator orchestrator = new VerlaTurnOrchestrator(
                null,
                conversationRepository,
                turnRepository,
                sessionRepository,
                messageRepository,
                new NoopAttachmentRepository(),
                new NoopArtifactRepository(),
                new TurnStateMachine(),
                new SessionStateMachine(),
                null,
                new ObjectMapper(),
                new NoopQuotaService(),
                entitlementService,
                event -> {},
                mockAnalyticsService());

        sessionRepository.session = VerlaSession.builder()
                .id(700L)
                .conversationId(74L)
                .turnId(801L)
                .status(SessionStatus.RUNNING.name())
                .kind(VerlaSessionKind.ASSIGNMENT_CHAT.name())
                .build();
        turnRepository.turn = VerlaTurn.builder()
                .id(801L)
                .conversationId(74L)
                .status(TurnStatus.RUNNING_AGENT.name())
                .build();

        orchestrator.onAssignmentChatCompleted(700L, Map.of("finalText", "done"));

        Mockito.verify(entitlementService).markFollowupEditCompleted(700L);
    }

    private static final class FakeSessionRepository implements VerlaSessionRepository {
        VerlaSession session;
        VerlaSession saved;
        List<VerlaSession> sessionsByTurn = List.of();

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
            if (!sessionsByTurn.isEmpty()) {
                return sessionsByTurn;
            }
            return session == null ? List.of() : List.of(session);
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
            return Map.of();
        }
    }

    private static AnalyticsService mockAnalyticsService() {
        return Mockito.mock(AnalyticsService.class);
    }

    private static EntitlementService mockEntitlementService() {
        return Mockito.mock(EntitlementService.class);
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

        @Override
        public List<VerlaTurn> findByIds(List<Long> turnIds) {
            return List.of();
        }

        @Override
        public Map<Long, List<VerlaTurn>> findRecentByConversationIds(List<Long> conversationIds) {
            return Map.of();
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
            if (saved != null && id.equals(saved.getId())) {
                return saved;
            }
            return savedMessages.stream()
                    .filter(message -> id.equals(message.getId()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<VerlaMessage> findByCursor(Long conversationId, Long cursor, int limit) {
            return List.of();
        }

        @Override
        public List<VerlaMessage> findFileChatByCursor(Long conversationId, String objectId, Long cursor, int limit) {
            return List.of();
        }

        @Override
        public List<VerlaMessage> findAssignmentChatByCursor(Long conversationId, Long cursor, int limit) {
            return List.of();
        }
    }

    private static final class NoopArtifactRepository
            implements com.studyagent.service.domain.verla.repo.VerlaArtifactRepository {
        @Override
        public com.studyagent.service.domain.verla.VerlaArtifact findById(Long id) {
            return null;
        }

        @Override
        public com.studyagent.service.domain.verla.VerlaArtifact findByUid(String artifactUid) {
            return null;
        }

        @Override
        public List<com.studyagent.service.domain.verla.VerlaArtifact> findByConversation(Long conversationId) {
            return List.of();
        }

        @Override
        public List<com.studyagent.service.domain.verla.VerlaArtifact> findBySession(Long sessionId) {
            return List.of();
        }

        @Override
        public List<com.studyagent.service.domain.verla.VerlaArtifact> findByUids(List<String> artifactUids) {
            return List.of();
        }

        @Override
        public com.studyagent.service.domain.verla.VerlaArtifact upsertByUid(
                com.studyagent.service.domain.verla.VerlaArtifact artifact) {
            return artifact;
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

    private static final class NoopQuotaService implements VerlaQuotaService {
        @Override
        public VerlaQuotaConsumeResult consumeForAssignmentRun(VerlaQuotaContext ctx) {
            return null;
        }

        @Override
        public VerlaQuotaConsumeResult consumeForDetection(VerlaQuotaContext ctx, String text) {
            return null;
        }

        @Override
        public VerlaQuotaConsumeResult consumeForHumanizer(VerlaQuotaContext ctx, String text) {
            return null;
        }

        @Override
        public void refundBySessionId(Long sessionId, String reason) {
        }

        @Override
        public boolean isQuotaExempt(String clerkUserId) {
            return false;
        }

        @Override
        public void assertSufficientForAssignmentRun(String clerkUserId) {
        }

        @Override
        public void inheritAssignmentQuotaLedger(Long targetSessionId, Long turnId) {
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

        @Override
        public void releaseClaim(Long id, String workerId) {
        }

        @Override
        public int countDeferredAssignmentRunAhead(Long id, LocalDateTime createdAt) {
            return 0;
        }

        @Override
        public int countDeferredCapabilityRunAhead(Long id, String action, LocalDateTime createdAt) {
            return 0;
        }
    }
}
