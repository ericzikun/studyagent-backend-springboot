package com.studyagent.api.controller.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.api.service.legacy.LegacyTaskAdapter;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.common.verla.id.LegacyConversationIdCodec;
import com.studyagent.service.application.verla.entitlement.EntitlementService;
import com.studyagent.service.application.verla.VerlaAttachmentService;
import com.studyagent.service.application.verla.VerlaFileChatService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.application.verla.dto.FileChatAnalysisState;
import com.studyagent.service.application.verla.dto.FileChatAnalysisStatus;
import com.studyagent.service.application.verla.dto.FileChatPanelFileView;
import com.studyagent.service.application.verla.dto.FileChatPanelMessageView;
import com.studyagent.service.application.verla.dto.FileChatPanelView;
import com.studyagent.service.application.verla.dto.SendMessageResult;
import com.studyagent.service.domain.verla.VerlaAttachment;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VerlaFileChatHttpBindingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StubAttachmentService attachmentService;
    private StubVerlaFileChatService fileChatService;
    private StubVerlaTurnOrchestrator turnOrchestrator;
    private LegacyTaskAdapter legacyTaskAdapter;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        attachmentService = new StubAttachmentService();
        fileChatService = new StubVerlaFileChatService();
        turnOrchestrator = new StubVerlaTurnOrchestrator();
        legacyTaskAdapter = mock(LegacyTaskAdapter.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new VerlaAttachmentController(attachmentService, legacyTaskAdapter),
                        new VerlaFileChatController(fileChatService, turnOrchestrator))
                .build();
    }

    @Test
    void attachmentsEndpoint_shouldBindCidAndReturnList() throws Exception {
        attachmentService.attachments = List.of(VerlaAttachment.builder()
                .objectId("obj_123")
                .conversationId(24L)
                .userId("user_1")
                .filename("calculus homework.pdf")
                .mime("application/pdf")
                .sizeBytes(15820L)
                .status("PARSED")
                .attachmentOrigin("USER_UPLOAD")
                .createdAt(LocalDateTime.of(2026, 5, 20, 9, 0))
                .build());

        mockMvc.perform(get("/v1/verla/conversations/24/attachments")
                        .requestAttr("clerkUserId", "user_1")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data[0].objectId").value("obj_123"))
                .andExpect(jsonPath("$.data[0].name").value("calculus homework.pdf"))
                .andExpect(jsonPath("$.data[0].attachmentOrigin").value("USER_UPLOAD"));

        assertThat(attachmentService.lastUserId).isEqualTo("user_1");
        assertThat(attachmentService.lastConversationId).isEqualTo(24L);
        assertThat(attachmentService.lastLimit).isEqualTo(50);
    }

    @Test
    void attachmentsEndpoint_shouldReturnEmptyListForLegacyConversation() throws Exception {
        long legacyConversationId = LegacyConversationIdCodec.encode(24L);

        mockMvc.perform(get("/v1/verla/conversations/{cid}/attachments", legacyConversationId)
                        .requestAttr("clerkUserId", "user_1")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));

        assertThat(attachmentService.lastConversationId).isNull();
        assertThat(attachmentService.lastLimit).isNull();
        verify(legacyTaskAdapter).requireOwnedCompleted("user_1", 24L);
    }

    @Test
    void fileChatEndpoints_shouldBindRouteAndBodyParams() throws Exception {
        fileChatService.view = FileChatPanelView.builder()
                .file(FileChatPanelFileView.builder()
                        .objectId("obj_123")
                        .name("calculus homework.pdf")
                        .mimeType("application/pdf")
                        .sizeBytes(15820L)
                        .extractStatus("PARSED")
                        .build())
                .analysis(FileChatAnalysisState.builder()
                        .status(FileChatAnalysisStatus.READY)
                        .text("这是题目文件。")
                        .build())
                .suggestedQuestions(List.of("帮我比较这四道题"))
                .messages(List.of(FileChatPanelMessageView.builder()
                        .messageId(1001L)
                        .role("user")
                        .text("帮我比较这四道题")
                        .createdAt(LocalDateTime.of(2026, 5, 20, 10, 2))
                        .build()))
                .nextCursor(1001L)
                .build();
        turnOrchestrator.result = SendMessageResult.builder()
                .turnId(456L)
                .userMessageId(1001L)
                .agentSessionId(789L)
                .build();

        mockMvc.perform(get("/v1/verla/conversations/24/file-chat")
                        .requestAttr("clerkUserId", "user_1")
                        .param("objectId", "obj_123")
                        .param("cursor", "500")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.file.objectId").value("obj_123"))
                .andExpect(jsonPath("$.data.analysis.status").value("READY"))
                .andExpect(jsonPath("$.data.messages[0].messageId").value(1001));

        mockMvc.perform(post("/v1/verla/conversations/24/file-chat/messages")
                        .requestAttr("clerkUserId", "user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "objectId": "obj_123",
                                  "message": "Compare the 4 essay prompts for me."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.turnId").value(VerlaPublicIdVoSupport.turn(456L, true)))
                .andExpect(jsonPath("$.data.userMessageId").value(VerlaPublicIdVoSupport.message(1001L, true)))
                .andExpect(jsonPath("$.data.agentSessionId").value(VerlaPublicIdVoSupport.session(789L, true)));

        assertThat(fileChatService.lastUserId).isEqualTo("user_1");
        assertThat(fileChatService.lastConversationId).isEqualTo(24L);
        assertThat(fileChatService.lastObjectId).isEqualTo("obj_123");
        assertThat(fileChatService.lastCursor).isEqualTo(500L);
        assertThat(fileChatService.lastLimit).isEqualTo(20);

        assertThat(turnOrchestrator.lastUserId).isEqualTo("user_1");
        assertThat(turnOrchestrator.lastConversationId).isEqualTo(24L);
        assertThat(turnOrchestrator.lastObjectId).isEqualTo("obj_123");
        assertThat(turnOrchestrator.lastMessage).isEqualTo("Compare the 4 essay prompts for me.");
    }

    private static final class StubAttachmentService extends VerlaAttachmentService {
        private String lastUserId;
        private Long lastConversationId;
        private Integer lastLimit;
        private List<VerlaAttachment> attachments = List.of();

        StubAttachmentService() {
            super(null, null, null, null, mock(EntitlementService.class), new SimpleMeterRegistry());
        }

        @Override
        public List<VerlaAttachment> listByConversation(String clerkUserId, long conversationId, int limit) {
            this.lastUserId = clerkUserId;
            this.lastConversationId = conversationId;
            this.lastLimit = limit;
            return attachments;
        }
    }

    private static final class StubVerlaFileChatService extends VerlaFileChatService {
        private String lastUserId;
        private Long lastConversationId;
        private String lastObjectId;
        private Long lastCursor;
        private Integer lastLimit;
        private FileChatPanelView view;

        StubVerlaFileChatService() {
            super(null, null, null);
        }

        @Override
        public FileChatPanelView getPanel(String userId, Long conversationId, String objectId, Long cursor, int limit) {
            this.lastUserId = userId;
            this.lastConversationId = conversationId;
            this.lastObjectId = objectId;
            this.lastCursor = cursor;
            this.lastLimit = limit;
            return view;
        }
    }

    private static final class StubVerlaTurnOrchestrator extends VerlaTurnOrchestrator {
        private String lastUserId;
        private Long lastConversationId;
        private String lastObjectId;
        private String lastMessage;
        private SendMessageResult result;

        StubVerlaTurnOrchestrator() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null,
                    mock(EntitlementService.class), event -> {}, null, null);
        }

        @Override
        public SendMessageResult startFileChat(String userId, Long conversationId, String objectId, String message) {
            this.lastUserId = userId;
            this.lastConversationId = conversationId;
            this.lastObjectId = objectId;
            this.lastMessage = message;
            return result;
        }
    }
}
