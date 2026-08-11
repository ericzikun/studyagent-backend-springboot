package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.FileChatSendMessageRequest;
import com.studyagent.api.dto.verla.response.FileChatPanelAnalysisVO;
import com.studyagent.api.dto.verla.response.FileChatPanelResponseVO;
import com.studyagent.api.dto.verla.response.FileChatSendMessageResponseVO;
import com.studyagent.api.dto.verla.response.FileChatMessageVO;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.service.application.verla.entitlement.EntitlementService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.application.verla.VerlaFileChatService;
import com.studyagent.service.application.verla.dto.FileChatAnalysisState;
import com.studyagent.service.application.verla.dto.FileChatAnalysisStatus;
import com.studyagent.service.application.verla.dto.FileChatPanelFileView;
import com.studyagent.service.application.verla.dto.FileChatPanelMessageView;
import com.studyagent.service.application.verla.dto.FileChatPanelView;
import com.studyagent.service.application.verla.dto.SendMessageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class VerlaFileChatControllerTest {

    private StubVerlaFileChatService fileChatService;
    private StubVerlaTurnOrchestrator turnOrchestrator;
    private VerlaFileChatController controller;

    @BeforeEach
    void setUp() {
        fileChatService = new StubVerlaFileChatService();
        turnOrchestrator = new StubVerlaTurnOrchestrator();
        controller = new VerlaFileChatController(fileChatService, turnOrchestrator);
    }

    @Test
    void getPanel_shouldMapParamsAndResponse() {
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

        Result<FileChatPanelResponseVO> result = controller.getPanel("user_1", 1001L, "obj_123", 500L, 200);

        assertThat(fileChatService.lastUserId).isEqualTo("user_1");
        assertThat(fileChatService.lastConversationId).isEqualTo(1001L);
        assertThat(fileChatService.lastObjectId).isEqualTo("obj_123");
        assertThat(fileChatService.lastCursor).isEqualTo(500L);
        assertThat(fileChatService.lastLimit).isEqualTo(100);
        assertThat(result.getMeta().getStatusCode()).isEqualTo(0);
        assertThat(result.getData().getFile().getObjectId()).isEqualTo("obj_123");
        assertThat(result.getData().getAnalysis()).isEqualTo(FileChatPanelAnalysisVO.builder()
                .status("READY")
                .text("这是题目文件。")
                .build());
        assertThat(result.getData().getMessages()).containsExactly(FileChatMessageVO.builder()
                .messageId(1001L)
                .role("user")
                .text("帮我比较这四道题")
                .createdAt(LocalDateTime.of(2026, 5, 20, 10, 2))
                .build());
        assertThat(result.getData().getNextCursor()).isEqualTo(1001L);
    }

    @Test
    void sendMessage_shouldMapRequestAndResponse() {
        SendMessageResult sendResult = SendMessageResult.builder()
                .turnId(456L)
                .userMessageId(1001L)
                .agentSessionId(789L)
                .build();
        turnOrchestrator.result = sendResult;
        FileChatSendMessageRequest request = new FileChatSendMessageRequest();
        request.setObjectId("obj_123");
        request.setMessage("Compare the 4 essay prompts for me.");

        Result<FileChatSendMessageResponseVO> result = controller.sendMessage("user_1", 1001L, request);

        assertThat(turnOrchestrator.lastUserId).isEqualTo("user_1");
        assertThat(turnOrchestrator.lastConversationId).isEqualTo(1001L);
        assertThat(turnOrchestrator.lastObjectId).isEqualTo("obj_123");
        assertThat(turnOrchestrator.lastMessage).isEqualTo("Compare the 4 essay prompts for me.");
        assertThat(result.getMeta().getStatusCode()).isEqualTo(0);
        assertThat(result.getData()).isEqualTo(FileChatSendMessageResponseVO.from(sendResult));
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
