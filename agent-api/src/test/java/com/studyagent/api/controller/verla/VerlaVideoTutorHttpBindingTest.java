package com.studyagent.api.controller.verla;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.application.verla.VideoTutorSnapshotService;
import com.studyagent.service.application.verla.dto.SendMessageResult;
import com.studyagent.service.application.verla.dto.VideoTutorSnapshotView;
import com.studyagent.service.application.verla.entitlement.EntitlementService;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Video Tutor HTTP 层：路由 / 请求体绑定 / 登录与归属校验 / 快照字段。
 * <p>
 * 这条测试的价值不在"能 200"，而在几处**静默失败**：缺 {@code clerkUserId} 时必须拒绝、
 * 快照必须带 {@code resumeAfterEventId}（少了它前端重连会从头重放整轮）、
 * 消息必须正序（反了左栏一问一答颠倒但依然有数据、不报错）。
 */
class VerlaVideoTutorHttpBindingTest {

    private StubVerlaTurnOrchestrator turnOrchestrator;
    private VerlaConversationService conversationService;
    private VideoTutorSnapshotService snapshotService;
    private VerlaMessageRepository messageRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        turnOrchestrator = new StubVerlaTurnOrchestrator();
        conversationService = mock(VerlaConversationService.class);
        snapshotService = mock(VideoTutorSnapshotService.class);
        messageRepository = mock(VerlaMessageRepository.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new VerlaVideoTutorController(
                        turnOrchestrator, conversationService, snapshotService, messageRepository))
                .build();
    }

    @Test
    void turnEndpoint_shouldBindRouteAndBodyAndReturnPublicIds() throws Exception {
        turnOrchestrator.result = SendMessageResult.builder()
                .turnId(456L).userMessageId(1001L).agentSessionId(789L).build();

        mockMvc.perform(post("/v1/verla/conversations/24/video-tutor/messages")
                        .requestAttr("clerkUserId", "user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "k 不存在怎么办？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.turnId").value(VerlaPublicIdVoSupport.turn(456L, true)))
                .andExpect(jsonPath("$.data.agentSessionId").value(
                        VerlaPublicIdVoSupport.session(789L, true)));

        assertThat(turnOrchestrator.lastUserId).isEqualTo("user_1");
        assertThat(turnOrchestrator.lastConversationId).isEqualTo(24L);
        assertThat(turnOrchestrator.lastMessage).isEqualTo("k 不存在怎么办？");
    }

    @Test
    void firstTurnWithoutStudentText_shouldStillBeAccepted() throws Exception {
        // 首轮学生没说话：空串必须能进到 orchestrator（Python 会回落成开场指令），
        // 这里加 @NotBlank 的话，第一次打开页面就发不出请求
        turnOrchestrator.result = SendMessageResult.builder().turnId(1L).agentSessionId(2L).build();

        mockMvc.perform(post("/v1/verla/conversations/24/video-tutor/messages")
                        .requestAttr("clerkUserId", "user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0));

        assertThat(turnOrchestrator.lastMessage).isEmpty();
    }

    @Test
    void cancelEndpoint_shouldBindSessionPublicId() throws Exception {
        turnOrchestrator.result = SendMessageResult.builder().turnId(456L).agentSessionId(789L).build();

        mockMvc.perform(post("/v1/verla/conversations/24/video-tutor/sessions/789/cancel")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0));

        assertThat(turnOrchestrator.lastCancelledSessionId).isEqualTo(789L);
        assertThat(turnOrchestrator.lastCancelledConversationId).isEqualTo(24L);
    }

    @Test
    void missingLoginAttribute_isRejectedBySpringBeforeReachingTheController() throws Exception {
        // 实测：@RequestAttribute 缺失时 Spring 自己就返回 400，压根到不了 ensureLogin。
        // 所以这里只能断状态码 —— 断 BusinessException 的那条路是走不到的。
        mockMvc.perform(post("/v1/verla/conversations/24/video-tutor/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isBadRequest());

        assertThat(turnOrchestrator.lastConversationId).isNull();
    }

    @Test
    void blankLoginAttribute_isRejectedByEnsureLogin() {
        // 属性在、但被填空串：这才是 ensureLogin 真正负责的情形。
        // standaloneSetup 没装 GlobalExceptionHandler，异常会包成 ServletException 抛出，故断言根因。
        Throwable thrown = assertThrows(Exception.class, () ->
                mockMvc.perform(post("/v1/verla/conversations/24/video-tutor/messages")
                        .requestAttr("clerkUserId", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}")));

        Throwable root = thrown;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(BusinessException.class);
        assertThat(turnOrchestrator.lastConversationId).isNull();
    }

    @Test
    void snapshotShouldCarryBoardReferenceCounterAndResumeCursor() throws Exception {
        when(snapshotService.getSnapshot(24L)).thenReturn(new VideoTutorSnapshotView(
                24L,
                987L,
                List.of(Map.of("ref", "b2", "op", "eq", "latex", "y=a(x-h)^2+k")),
                7,
                3,
                List.of(VerlaMessage.builder()
                        .id(201L).conversationId(24L).turnId(456L).role("assistant")
                        .sourceSessionId(789L).textContent("顶点是 (h,k)。")
                        .scene("VIDEO_TUTOR").createdAt(LocalDateTime.parse("2026-10-10T09:00:00"))
                        .build())));

        mockMvc.perform(get("/v1/verla/conversations/24/video-tutor/snapshot")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.conversationId").value(
                        VerlaPublicIdVoSupport.conversation(24L, true)))
                // 少了它前端重连会从 0 重放整轮事件
                .andExpect(jsonPath("$.data.resumeAfterEventId").value(987))
                // 前端必须用后端给的计数器，别自己数
                .andExpect(jsonPath("$.data.refSeq").value(7))
                .andExpect(jsonPath("$.data.boardVersion").value(3))
                .andExpect(jsonPath("$.data.board[0].ref").value("b2"))
                .andExpect(jsonPath("$.data.messages[0].text").value("顶点是 (h,k)。"));

        verify(conversationService).getOwned("user_1", 24L);
    }

    @Test
    void snapshotShouldRejectForeignConversationWithoutReadingIt() {
        Mockito.doThrow(new RuntimeException("not owner"))
                .when(conversationService).getOwned("user_1", 24L);

        assertThrows(Exception.class, () ->
                mockMvc.perform(get("/v1/verla/conversations/24/video-tutor/snapshot")
                        .requestAttr("clerkUserId", "user_1")));

        verify(snapshotService, never()).getSnapshot(any());
    }

    @Test
    void historyEndpoint_shouldPageByCursorAndKeepOldestAsNextCursor() throws Exception {
        when(messageRepository.findVideoTutorByCursor(24L, 300L, 2)).thenReturn(List.of(
                VerlaMessage.builder().id(201L).conversationId(24L).turnId(456L).role("assistant")
                        .textContent("新的").scene("VIDEO_TUTOR")
                        .createdAt(LocalDateTime.parse("2026-10-10T09:01:00")).build(),
                VerlaMessage.builder().id(200L).conversationId(24L).turnId(455L).role("user")
                        .textContent("旧的").scene("VIDEO_TUTOR")
                        .createdAt(LocalDateTime.parse("2026-10-10T09:00:00")).build()));

        mockMvc.perform(get("/v1/verla/conversations/24/video-tutor/messages")
                        .requestAttr("clerkUserId", "user_1")
                        .param("cursor", "300")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].text").value("新的"))
                .andExpect(jsonPath("$.data.nextCursor").value(200));

        verify(conversationService).getOwned("user_1", 24L);
        verify(messageRepository).findVideoTutorByCursor(eq(24L), eq(300L), eq(2));
    }

    @Test
    void historyEndpoint_shouldClampLimitAndTolerateNoHistory() throws Exception {
        when(messageRepository.findVideoTutorByCursor(eq(24L), eq((Long) null), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/verla/conversations/24/video-tutor/messages")
                        .requestAttr("clerkUserId", "user_1")
                        .param("limit", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());

        verify(messageRepository).findVideoTutorByCursor(24L, null, 100);
    }

    private static final class StubVerlaTurnOrchestrator extends VerlaTurnOrchestrator {
        private String lastUserId;
        private Long lastConversationId;
        private String lastMessage;
        private Long lastCancelledSessionId;
        private Long lastCancelledConversationId;
        private SendMessageResult result;

        StubVerlaTurnOrchestrator() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null,
                    mock(EntitlementService.class), null, event -> { }, null);
        }

        @Override
        public SendMessageResult startVideoTutorTurn(
                String userId, Long conversationId, String message, String outputLanguage) {
            this.lastUserId = userId;
            this.lastConversationId = conversationId;
            this.lastMessage = message;
            return result;
        }

        @Override
        public SendMessageResult cancelVideoTutorTurn(String userId, Long conversationId, Long sessionId) {
            this.lastUserId = userId;
            this.lastCancelledConversationId = conversationId;
            this.lastCancelledSessionId = sessionId;
            return result;
        }
    }
}
