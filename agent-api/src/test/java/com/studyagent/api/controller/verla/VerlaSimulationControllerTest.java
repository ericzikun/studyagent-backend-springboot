package com.studyagent.api.controller.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infra.service.simulation.SimulationEntitlementService;
import com.studyagent.infra.service.simulation.SimulationPassService;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.domain.file.OssStorageService;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerlaSimulationControllerTest {

    private VerlaConversationService conversationService;
    private VerlaTurnOrchestrator turnOrchestrator;
    private VerlaTurnRepository turnRepository;
    private VerlaSessionRepository sessionRepository;
    private VerlaArtifactRepository artifactRepository;
    private VerlaMessageRepository messageRepository;
    private VerlaAttachmentRepository attachmentRepository;
    private JdbcTemplate jdbcTemplate;
    private SimulationPassService simulationPassService;
    private SimulationEntitlementService entitlementService;
    private VerlaSimulationController controller;

    @BeforeEach
    void setUp() {
        conversationService = mock(VerlaConversationService.class);
        turnOrchestrator = mock(VerlaTurnOrchestrator.class);
        turnRepository = mock(VerlaTurnRepository.class);
        sessionRepository = mock(VerlaSessionRepository.class);
        artifactRepository = mock(VerlaArtifactRepository.class);
        messageRepository = mock(VerlaMessageRepository.class);
        attachmentRepository = mock(VerlaAttachmentRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        simulationPassService = mock(SimulationPassService.class);
        entitlementService = mock(SimulationEntitlementService.class);
        when(simulationPassService.access(anyString(), anyBoolean())).thenReturn(
                new SimulationPassService.Access(
                        true, false, true, "simulation_lab_7d", null, 199, "usd", 7, false));
        when(entitlementService.resolve(anyString())).thenReturn(
                new SimulationEntitlementService.Entitlement(
                        true, false, SimulationEntitlementService.ENTITLEMENT_PASS, true,
                        null, 0, null, null, null,
                        false, false, false, false, true, null, true));
        controller = new VerlaSimulationController(
                conversationService,
                turnOrchestrator,
                turnRepository,
                sessionRepository,
                artifactRepository,
                messageRepository,
                attachmentRepository,
                mock(OssStorageService.class),
                new ObjectMapper(),
                jdbcTemplate,
                simulationPassService,
                entitlementService);
    }

    @Test
    void configExposesLimitsForAnyLoggedInUser() {
        Result<Map<String, Object>> result = controller.config("user_1");
        Result<Map<String, Object>> other = controller.config("user_2");

        assertEquals(Boolean.TRUE, result.getData().get("canCreate"));
        assertEquals(Boolean.TRUE, other.getData().get("canCreate"));
        assertEquals(3, result.getData().get("maxFiles"));
        assertEquals(1, result.getData().get("maxActiveRuns"));
        assertEquals(50, result.getData().get("maxDailyRuns"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void configExposesFreeTrialAndPreviewLock() {
        when(entitlementService.resolve("free_1")).thenReturn(
                new SimulationEntitlementService.Entitlement(
                        true, false, SimulationEntitlementService.ENTITLEMENT_FREE_TRIAL, false,
                        1L, 0L, 1L, null, null,
                        true, false, false, false, false, null, true));
        when(simulationPassService.access(eq("free_1"), eq(false))).thenReturn(
                new SimulationPassService.Access(
                        false, false, true, "simulation_lab_7d", null, 199, "usd", 7, false));

        Result<Map<String, Object>> free = controller.config("free_1");

        assertEquals(Boolean.TRUE, free.getData().get("canCreate"));
        assertEquals("free_trial", free.getData().get("entitlement"));
        assertEquals(Boolean.FALSE, free.getData().get("previewLocked"));
        Map<String, Object> pass = (Map<String, Object>) free.getData().get("pass");
        assertEquals(Boolean.FALSE, pass.get("active"));
        assertEquals(Boolean.TRUE, pass.get("freeTrialAvailable"));

        when(entitlementService.resolve("free_2")).thenReturn(
                new SimulationEntitlementService.Entitlement(
                        false, true, SimulationEntitlementService.ENTITLEMENT_FREE_TRIAL_USED, false,
                        1L, 1L, 0L, null, null,
                        false, true, false, false, false, null, true));

        Result<Map<String, Object>> used = controller.config("free_2");

        assertEquals(Boolean.FALSE, used.getData().get("canCreate"));
        assertEquals(Boolean.TRUE, used.getData().get("previewLocked"));
        assertEquals("free_trial_used", used.getData().get("entitlement"));
    }

    @Test
    void hiddenGuardKeepsGeneratedFallbacksInvisible() {
        String html = "<!DOCTYPE html><html><head>"
                + "<style>.fallback{position:absolute;inset:0;display:flex}</style>"
                + "</head><body><canvas></canvas>"
                + "<div class=\"fallback\" hidden>3D 渲染不可用</div></body></html>";

        String guarded = VerlaSimulationController.ensureHiddenGuard(html);

        assertTrue(guarded.contains("[hidden]{display:none!important}"));
        assertEquals(guarded, VerlaSimulationController.ensureHiddenGuard(guarded));
    }

    @Test
    void createStoresSimulationIntentSoHistoryIsNotListedAsAssignment() {
        VerlaConversation conversation = VerlaConversation.builder()
                .id(42L)
                .userId("user_1")
                .title("Pendulum lab")
                .build();
        when(conversationService.create(
                eq("user_1"), anyString(), eq("{\"mode\":\"simulation\"}"), eq("SIMULATION")))
                .thenReturn(conversation);

        Result<Map<String, Object>> result = controller.create(
                "user_1", new VerlaSimulationController.CreateSimulationRequest("make a pendulum lab"));

        assertEquals("DRAFT", result.getData().get("status"));
        verify(conversationService).create(
                eq("user_1"), anyString(), eq("{\"mode\":\"simulation\"}"), eq("SIMULATION"));
    }

    @Test
    void createRequiresSimulationPass() {
        when(entitlementService.resolve("user_1")).thenReturn(
                new SimulationEntitlementService.Entitlement(
                        false, false, SimulationEntitlementService.ENTITLEMENT_FREE_TRIAL_USED, false,
                        1L, 1L, 0L, null, null,
                        false, true, false, false, false, null, true));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.create("user_1",
                        new VerlaSimulationController.CreateSimulationRequest("make a lab")));

        assertEquals(ApiCode.SIMULATION_PASS_REQUIRED.getCode(), error.getCode());
        verify(conversationService, never()).create(anyString(), anyString(), anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void paidMemberGetsAccessWithoutPurchasingPass() {
        when(entitlementService.resolve("member_1")).thenReturn(
                new SimulationEntitlementService.Entitlement(
                        true, false, SimulationEntitlementService.ENTITLEMENT_MEMBERSHIP, false,
                        16L, 0L, 16L, LocalDateTime.now(), LocalDateTime.now().plusMonths(1),
                        false, false, true, false, false, null, false));
        when(simulationPassService.access(eq("member_1"), eq(true))).thenReturn(
                new SimulationPassService.Access(
                        true, false, false, "simulation_lab_7d", null, 199, "usd", 7, true));

        Result<Map<String, Object>> result = controller.config("member_1");

        assertEquals(Boolean.TRUE, result.getData().get("canCreate"));
        Map<String, Object> pass = (Map<String, Object>) result.getData().get("pass");
        assertEquals(Boolean.TRUE, pass.get("active"));
        assertEquals(Boolean.FALSE, pass.get("purchasable"));
        assertEquals(Boolean.TRUE, pass.get("membershipIncluded"));
    }

    @Test
    void paidMemberCannotBuySimulationPass() {
        when(entitlementService.resolve("member_1")).thenReturn(
                new SimulationEntitlementService.Entitlement(
                        true, false, SimulationEntitlementService.ENTITLEMENT_MEMBERSHIP, false,
                        16L, 0L, 16L, LocalDateTime.now(), LocalDateTime.now().plusMonths(1),
                        false, false, true, false, false, null, false));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.passCheckout("member_1"));

        assertEquals(ApiCode.ILLEGAL_STATE.getCode(), error.getCode());
        verify(simulationPassService, never()).createCheckout(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void configUnderPaymentRecoveryHidesPassPurchaseAndBlocksCreate() {
        when(entitlementService.resolve("user_recovery")).thenReturn(
                new SimulationEntitlementService.Entitlement(
                        false, true, SimulationEntitlementService.ENTITLEMENT_PAYMENT_RECOVERY, false,
                        0L, 0L, 0L, null, null,
                        false, false, false, false, true, null, false));
        when(simulationPassService.access(eq("user_recovery"), anyBoolean())).thenReturn(
                new SimulationPassService.Access(
                        true, false, true, "simulation_lab_7d", null, 199, "usd", 7, false));

        Result<Map<String, Object>> result = controller.config("user_recovery");

        assertEquals(Boolean.FALSE, result.getData().get("canCreate"));
        assertEquals(Boolean.TRUE, result.getData().get("previewLocked"));
        assertEquals(SimulationEntitlementService.ENTITLEMENT_PAYMENT_RECOVERY, result.getData().get("entitlement"));
        Map<String, Object> pass = (Map<String, Object>) result.getData().get("pass");
        assertEquals(Boolean.FALSE, pass.get("purchasable"));
    }

    @Test
    void createUnderPaymentRecoveryReturnsTyped1033InsteadOfPassRequired() {
        when(entitlementService.resolve("user_recovery")).thenReturn(
                new SimulationEntitlementService.Entitlement(
                        false, true, SimulationEntitlementService.ENTITLEMENT_PAYMENT_RECOVERY, false,
                        0L, 0L, 0L, null, null,
                        false, false, false, false, false, null, false));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.create("user_recovery",
                        new VerlaSimulationController.CreateSimulationRequest("make a lab")));

        assertEquals(ApiCode.PAYMENT_RECOVERY_REQUIRED.getCode(), error.getCode());
        assertTrue(error.getCode() != ApiCode.SIMULATION_PASS_REQUIRED.getCode());
        verify(conversationService, never()).create(anyString(), anyString(), anyString(), any());
    }

    @Test
    void passCheckoutUnderPaymentRecoveryReturnsTyped1033() {
        when(entitlementService.resolve("user_recovery")).thenReturn(
                new SimulationEntitlementService.Entitlement(
                        false, true, SimulationEntitlementService.ENTITLEMENT_PAYMENT_RECOVERY, false,
                        0L, 0L, 0L, null, null,
                        false, false, false, false, true, null, false));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.passCheckout("user_recovery"));

        assertEquals(ApiCode.PAYMENT_RECOVERY_REQUIRED.getCode(), error.getCode());
        verify(simulationPassService, never()).createCheckout(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void compedPaidMemberWithoutStripeIdGetsAccess() {
        when(entitlementService.resolve("member_2")).thenReturn(
                new SimulationEntitlementService.Entitlement(
                        true, false, SimulationEntitlementService.ENTITLEMENT_MEMBERSHIP, false,
                        16L, 0L, 16L, LocalDateTime.now(), LocalDateTime.now().plusMonths(1),
                        false, false, true, false, false, null, false));
        when(simulationPassService.access(eq("member_2"), eq(true))).thenReturn(
                new SimulationPassService.Access(
                        true, false, false, "simulation_lab_7d", null, 199, "usd", 7, true));

        Result<Map<String, Object>> result = controller.config("member_2");

        assertEquals(Boolean.TRUE, result.getData().get("canCreate"));
        Map<String, Object> pass = (Map<String, Object>) result.getData().get("pass");
        assertEquals(Boolean.TRUE, pass.get("membershipIncluded"));
        assertEquals(Boolean.FALSE, pass.get("purchasable"));
    }

    @Test
    void generateRejectsArchivedConversationWithOtherIntent() {
        VerlaConversation conversation = VerlaConversation.builder()
                .id(11L)
                .userId("user_1")
                .primaryIntent("ASSIGNMENT")
                .build();
        when(conversationService.getOwned("user_1", 11L)).thenReturn(conversation);

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.generate("user_1", 11L,
                        new VerlaSimulationController.GenerateSimulationRequest("make a lab", null, "en")));

        assertEquals(ApiCode.ILLEGAL_STATE.getCode(), error.getCode());
        verify(turnOrchestrator, never()).onUserMessage(any());
    }

    @Test
    void generateRejectsSecondTurn() {
        VerlaConversation conversation = VerlaConversation.builder()
                .id(11L)
                .userId("user_1")
                .primaryIntent("SIMULATION")
                .lastTurnId(5L)
                .turnCount(1)
                .build();
        when(conversationService.getOwned("user_1", 11L)).thenReturn(conversation);
        when(artifactRepository.findByConversation(11L)).thenReturn(List.of(
                VerlaArtifact.builder().kind("simulation_html").status("READY").build()));

        assertThrows(BusinessException.class,
                () -> controller.generate("user_1", 11L,
                        new VerlaSimulationController.GenerateSimulationRequest("again", null, "en")));
        verify(turnOrchestrator, never()).onUserMessage(any());
    }

    @Test
    void generateRejectsTooManyAttachments() {
        VerlaConversation conversation = VerlaConversation.builder()
                .id(11L)
                .userId("user_1")
                .primaryIntent("SIMULATION")
                .build();
        when(conversationService.getOwned("user_1", 11L)).thenReturn(conversation);

        String attachments = "[{\"objectId\":\"a\"},{\"objectId\":\"b\"},"
                + "{\"objectId\":\"c\"},{\"objectId\":\"d\"}]";
        assertThrows(BusinessException.class,
                () -> controller.generate("user_1", 11L,
                        new VerlaSimulationController.GenerateSimulationRequest(
                                "make a lab", attachments, "en")));
        verify(turnOrchestrator, never()).onUserMessage(any());
    }

    @Test
    void generateRejectsAttachmentFromAnotherConversation() {
        VerlaConversation conversation = VerlaConversation.builder()
                .id(11L)
                .userId("user_1")
                .primaryIntent("SIMULATION")
                .build();
        when(conversationService.getOwned("user_1", 11L)).thenReturn(conversation);
        when(attachmentRepository.findByObjectId("att_x")).thenReturn(
                com.studyagent.service.domain.verla.VerlaAttachment.builder()
                        .objectId("att_x")
                        .conversationId(99L)
                        .userId("user_2")
                        .build());

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.generate("user_1", 11L,
                        new VerlaSimulationController.GenerateSimulationRequest(
                                "make a lab", "[{\"objectId\":\"att_x\"}]", "en")));

        assertEquals(ApiCode.NO_PERMISSION.getCode(), error.getCode());
        verify(turnOrchestrator, never()).onUserMessage(any());
    }

    @Test
    void generateRejectsWhileAnotherRunIsActive() {
        VerlaConversation conversation = VerlaConversation.builder()
                .id(11L)
                .userId("user_1")
                .primaryIntent("SIMULATION")
                .build();
        when(conversationService.getOwned("user_1", 11L)).thenReturn(conversation);
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any()))
                .thenReturn(1);

        assertThrows(BusinessException.class,
                () -> controller.generate("user_1", 11L,
                        new VerlaSimulationController.GenerateSimulationRequest("make a lab", null, "en")));
        verify(turnOrchestrator, never()).onUserMessage(any());
    }

    @Test
    void messagesExposeStreamingThinkingSnapshot() {
        VerlaConversation conversation = VerlaConversation.builder()
                .id(11L)
                .userId("user_1")
                .build();
        when(conversationService.getOwned("user_1", 11L)).thenReturn(conversation);
        VerlaMessage assistant = VerlaMessage.builder()
                .id(21L)
                .role("assistant")
                .textContent("")
                .scene("SIMULATION_CHAT")
                .metaJson("{\"scene\":\"SIMULATION_CHAT\",\"streaming\":true,"
                        + "\"thinking\":\"先看页面结构\",\"thinkingStreaming\":true}")
                .createdAt(LocalDateTime.now())
                .build();
        when(messageRepository.findByCursor(11L, null, 100)).thenReturn(List.of(assistant));

        Result<List<Map<String, Object>>> result = controller.messages("user_1", 11L);
        Map<String, Object> row = result.getData().get(0);

        assertEquals("先看页面结构", row.get("thinking"));
        assertEquals(Boolean.TRUE, row.get("thinkingStreaming"));
        assertEquals(Boolean.TRUE, row.get("streaming"));
    }

    @Test
    void statusMapsRunningSessionToGenerating() {
        VerlaConversation conversation = VerlaConversation.builder()
                .id(11L)
                .userId("user_1")
                .lastTurnId(5L)
                .build();
        VerlaTurn turn = VerlaTurn.builder().id(5L).agentSessionId(7L).build();
        VerlaSession session = VerlaSession.builder().id(7L).status("RUNNING").kind("SIMULATION").build();
        when(conversationService.getOwned("user_1", 11L)).thenReturn(conversation);
        when(artifactRepository.findByConversation(11L)).thenReturn(List.of());
        when(turnRepository.findById(5L)).thenReturn(turn);
        when(sessionRepository.findById(7L)).thenReturn(session);

        Result<Map<String, Object>> result = controller.status("user_1", 11L);

        assertEquals("GENERATING", result.getData().get("status"));
    }

    @Test
    void deleteRejectsReadyArtifact() {
        VerlaConversation conversation = VerlaConversation.builder()
                .id(11L)
                .userId("user_1")
                .build();
        VerlaArtifact artifact = VerlaArtifact.builder()
                .artifactUid("artifact_11_5_7_simulation_html")
                .kind("simulation_html")
                .status("READY")
                .build();
        when(conversationService.getOwned("user_1", 11L)).thenReturn(conversation);
        when(artifactRepository.findByConversation(11L)).thenReturn(List.of(artifact));

        assertThrows(BusinessException.class, () -> controller.deleteDraft("user_1", 11L));
        verify(conversationService, never()).softDelete(anyString(), anyLong());
    }
}
