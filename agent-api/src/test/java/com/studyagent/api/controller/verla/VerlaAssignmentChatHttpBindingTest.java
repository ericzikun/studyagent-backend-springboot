package com.studyagent.api.controller.verla;

import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.application.verla.dto.SendMessageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VerlaAssignmentChatHttpBindingTest {

    private StubVerlaTurnOrchestrator turnOrchestrator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        turnOrchestrator = new StubVerlaTurnOrchestrator();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new VerlaAssignmentChatController(turnOrchestrator))
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
                .andExpect(jsonPath("$.data.turnId").value(456))
                .andExpect(jsonPath("$.data.userMessageId").value(1001))
                .andExpect(jsonPath("$.data.agentSessionId").value(789));

        assertThat(turnOrchestrator.lastUserId).isEqualTo("user_1");
        assertThat(turnOrchestrator.lastConversationId).isEqualTo(24L);
        assertThat(turnOrchestrator.lastMessage).isEqualTo("帮我修改正文");
        assertThat(turnOrchestrator.lastArtifactUids).containsExactly("art_md", "art_code");

        mockMvc.perform(post("/v1/verla/conversations/24/assignment-chat/sessions/789/cancel")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.agentSessionId").value(789));

        assertThat(turnOrchestrator.lastCancelledSessionId).isEqualTo(789L);

        mockMvc.perform(post("/v1/verla/conversations/24/assignment-chat/messages/456/retry")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.turnId").value(456));

        assertThat(turnOrchestrator.lastRetriedTurnId).isEqualTo(456L);
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
            super(null, null, null, null, null, null, null, null, null, null, null, null, event -> {});
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
