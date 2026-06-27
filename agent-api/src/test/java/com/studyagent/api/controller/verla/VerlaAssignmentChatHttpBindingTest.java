package com.studyagent.api.controller.verla;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.api.service.legacy.LegacyTaskAdapter;
import com.studyagent.common.verla.id.LegacyConversationIdCodec;
import com.studyagent.service.application.verla.entitlement.EntitlementService;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.application.verla.dto.SendMessageResult;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VerlaAssignmentChatHttpBindingTest {

    private StubVerlaTurnOrchestrator turnOrchestrator;
    private VerlaConversationService conversationService;
    private VerlaMessageRepository messageRepository;
    private LegacyTaskAdapter legacyTaskAdapter;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        turnOrchestrator = new StubVerlaTurnOrchestrator();
        conversationService = mock(VerlaConversationService.class);
        messageRepository = mock(VerlaMessageRepository.class);
        legacyTaskAdapter = mock(LegacyTaskAdapter.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new VerlaAssignmentChatController(
                                turnOrchestrator, conversationService, messageRepository, legacyTaskAdapter))
                .build();
    }

    @Test
    void assignmentChatEndpoints_shouldBindRouteAndBodyParams() throws Exception {
        turnOrchestrator.result = SendMessageResult.builder()
                .turnId(456L)
                .userMessageId(1001L)
                .agentSessionId(789L)
                .build();

        mockMvc.perform(post("/v1/verla/conversations/24/assignment-chat/messages")
                        .requestAttr("clerkUserId", "user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "帮我修改正文",
                                  "artifactUids": ["art_md", "art_code"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.turnId").value(VerlaPublicIdVoSupport.turn(456L, true)))
                .andExpect(jsonPath("$.data.userMessageId").value(VerlaPublicIdVoSupport.message(1001L, true)))
                .andExpect(jsonPath("$.data.agentSessionId").value(VerlaPublicIdVoSupport.session(789L, true)));

        assertThat(turnOrchestrator.lastUserId).isEqualTo("user_1");
        assertThat(turnOrchestrator.lastConversationId).isEqualTo(24L);
        assertThat(turnOrchestrator.lastMessage).isEqualTo("帮我修改正文");
        assertThat(turnOrchestrator.lastArtifactUids).containsExactly("art_md", "art_code");

        mockMvc.perform(post("/v1/verla/conversations/24/assignment-chat/sessions/789/cancel")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.agentSessionId").value(VerlaPublicIdVoSupport.session(789L, true)));

        assertThat(turnOrchestrator.lastCancelledSessionId).isEqualTo(789L);

        mockMvc.perform(post("/v1/verla/conversations/24/assignment-chat/messages/456/retry")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.turnId").value(VerlaPublicIdVoSupport.turn(456L, true)));

        assertThat(turnOrchestrator.lastRetriedTurnId).isEqualTo(456L);
    }

    @Test
    void assignmentChatHistoryEndpoint_shouldAuthorizeAndReturnPagedMessages() throws Exception {
        when(messageRepository.findAssignmentChatByCursor(24L, 300L, 2)).thenReturn(List.of(
                VerlaMessage.builder()
                        .id(201L)
                        .conversationId(24L)
                        .turnId(456L)
                        .role("assistant")
                        .sourceSessionId(789L)
                        .textContent("Done")
                        .blocksJson("{\"eventType\":\"ASSIGNMENT_CHAT_COMPLETED\",\"finalText\":\"Done\"}")
                        .scene("ASSIGNMENT_CHAT")
                        .createdAt(LocalDateTime.parse("2026-06-22T10:15:30"))
                        .build(),
                VerlaMessage.builder()
                        .id(200L)
                        .conversationId(24L)
                        .turnId(455L)
                        .role("user")
                        .textContent("Please review it")
                        .scene("ASSIGNMENT_CHAT")
                        .createdAt(LocalDateTime.parse("2026-06-22T10:14:30"))
                        .build()));

        mockMvc.perform(get("/v1/verla/conversations/24/assignment-chat/messages")
                        .requestAttr("clerkUserId", "user_1")
                        .param("cursor", "300")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.items[0].messageId").value(
                        VerlaPublicIdVoSupport.message(201L, true)))
                .andExpect(jsonPath("$.data.items[0].turnId").value(
                        VerlaPublicIdVoSupport.turn(456L, true)))
                .andExpect(jsonPath("$.data.items[0].role").value("assistant"))
                .andExpect(jsonPath("$.data.items[0].sourceSessionId").value(
                        VerlaPublicIdVoSupport.session(789L, true)))
                .andExpect(jsonPath("$.data.items[0].text").value("Done"))
                .andExpect(jsonPath("$.data.items[0].blocksJson").value(
                        "{\"eventType\":\"ASSIGNMENT_CHAT_COMPLETED\",\"finalText\":\"Done\"}"))
                .andExpect(jsonPath("$.data.nextCursor").value(200));

        verify(conversationService).getOwned("user_1", 24L);
        verify(messageRepository).findAssignmentChatByCursor(eq(24L), eq(300L), eq(2));
    }

    // task 4.4: 空历史返回空列表而非报错。
    @Test
    void assignmentChatHistoryEndpoint_shouldReturnEmptyListForNoHistory() throws Exception {
        when(messageRepository.findAssignmentChatByCursor(eq(24L), eq((Long) null), eq(20)))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/verla/conversations/24/assignment-chat/messages")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        verify(conversationService).getOwned("user_1", 24L);
    }

    @Test
    void assignmentChatHistoryEndpoint_shouldReturnEmptyListForLegacyConversation() throws Exception {
        long legacyConversationId = LegacyConversationIdCodec.encode(24L);

        mockMvc.perform(get("/v1/verla/conversations/{cid}/assignment-chat/messages", legacyConversationId)
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        verify(conversationService, never()).getOwned("user_1", legacyConversationId);
        verify(legacyTaskAdapter).requireOwnedCompleted("user_1", 24L);
        verify(messageRepository, never()).findAssignmentChatByCursor(
                any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // task 4.4: 他人 conversation 拒绝——所有权校验失败时不查询历史。
    @Test
    void assignmentChatHistoryEndpoint_shouldRejectForeignConversation() {
        Mockito.doThrow(new RuntimeException("not owner"))
                .when(conversationService).getOwned("user_1", 24L);

        assertThrows(Exception.class, () ->
                mockMvc.perform(get("/v1/verla/conversations/24/assignment-chat/messages")
                        .requestAttr("clerkUserId", "user_1")));

        verify(messageRepository, never()).findAssignmentChatByCursor(
                any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    private static final class StubVerlaTurnOrchestrator extends VerlaTurnOrchestrator {
        private String lastUserId;
        private Long lastConversationId;
        private String lastMessage;
        private List<String> lastArtifactUids;
        private Long lastCancelledSessionId;
        private Long lastRetriedTurnId;
        private SendMessageResult result;

        StubVerlaTurnOrchestrator() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null,
                    mock(EntitlementService.class), event -> {}, null);
        }

        @Override
        public SendMessageResult startAssignmentChat(
                String userId,
                Long conversationId,
                String message,
                List<String> artifactUids) {
            this.lastUserId = userId;
            this.lastConversationId = conversationId;
            this.lastMessage = message;
            this.lastArtifactUids = artifactUids;
            return result;
        }

        @Override
        public SendMessageResult cancelAssignmentChat(String userId, Long conversationId, Long sessionId) {
            this.lastUserId = userId;
            this.lastConversationId = conversationId;
            this.lastCancelledSessionId = sessionId;
            return result;
        }

        @Override
        public SendMessageResult retryAssignmentChat(String userId, Long conversationId, Long turnId) {
            this.lastUserId = userId;
            this.lastConversationId = conversationId;
            this.lastRetriedTurnId = turnId;
            return result;
        }
    }
}
