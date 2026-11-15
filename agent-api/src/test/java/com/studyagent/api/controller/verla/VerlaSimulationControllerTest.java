package com.studyagent.api.controller.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infra.service.simulation.SimulationPassService;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.domain.billing.BillingDomainService;
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
    private BillingDomainService billingDomainService;
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
        billingDomainService = mock(BillingDomainService.class);
        when(billingDomainService.isPaidMember(anyString())).thenReturn(false);
        when(simulationPassService.access(anyString(), anyBoolean())).thenReturn(
                new SimulationPassService.Access(
                        true, false, true, "simulation_lab_7d", null, 199, "usd", 7, false));
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
                billingDomainService);
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
    void createRequiresSimulationPass() {
        when(simulationPassService.access(eq("user_1"), anyBoolean())).thenReturn(
                new SimulationPassService.Access(
                        false, false, true, "simulation_lab_7d", null, 199, "usd", 7, false));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.create("user_1",
                        new VerlaSimulationController.CreateSimulationRequest("make a lab")));

        assertEquals(ApiCode.SIMULATION_PASS_REQUIRED.getCode(), error.getCode());
        verify(conversationService, never()).create(anyString(), anyString(), anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void paidMemberGetsAccessWithoutPurchasingPass() {
        when(billingDomainService.isPaidMember("member_1")).thenReturn(true);
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
        when(billingDomainService.isPaidMember("member_1")).thenReturn(true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.passCheckout("member_1"));

        assertEquals(ApiCode.ILLEGAL_STATE.getCode(), error.getCode());
        verify(simulationPassService, never()).createCheckout(anyString());
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
