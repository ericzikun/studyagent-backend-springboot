package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.SendMessageRequest;
import com.studyagent.api.dto.verla.response.SendMessageResponseVO;
import com.studyagent.api.dto.verla.response.VerlaConversationPageVO;
import com.studyagent.api.service.VerlaEditorPreviewService;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
import com.studyagent.service.application.verla.AssignmentRuntimeSnapshotService;
import com.studyagent.service.application.verla.VerlaAttachmentParseGate;
import com.studyagent.service.application.verla.VerlaConversationDashboardStatusService;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.application.verla.dto.SendMessageResult;
import com.studyagent.service.application.verla.dto.VerlaConversationListSlice;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import com.studyagent.service.domain.verla.state.ConversationStatus;
import com.studyagent.service.domain.verla.state.IntentLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerlaConversationControllerTest {

    private VerlaConversationService conversationService;
    private VerlaConversationDashboardStatusService dashboardStatusService;
    private VerlaArtifactMapper artifactMapper;
    private VerlaTurnOrchestrator turnOrchestrator;
    private VerlaAttachmentParseGate attachmentParseGate;
    private VerlaTurnRepository turnRepository;
    private VerlaConversationController controller;

    @BeforeEach
    void setUp() {
        conversationService = mock(VerlaConversationService.class);
        dashboardStatusService = mock(VerlaConversationDashboardStatusService.class);
        artifactMapper = mock(VerlaArtifactMapper.class);
        turnOrchestrator = mock(VerlaTurnOrchestrator.class);
        attachmentParseGate = mock(VerlaAttachmentParseGate.class);
        turnRepository = mock(VerlaTurnRepository.class);
        controller = new VerlaConversationController(
                conversationService,
                dashboardStatusService,
                mock(VerlaEditorPreviewService.class),
                artifactMapper,
                mock(AssignmentRuntimeSnapshotService.class),
                mock(com.studyagent.service.application.verla.AiWritingRuntimeSnapshotService.class),
                turnOrchestrator,
                attachmentParseGate,
                turnRepository,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.studyagent.api.service.legacy.LegacyTaskAdapter.class));
    }

    @Test
    void search_shouldAttachArtifactPreviewKindsForAssignmentConversations() {
        VerlaConversation conversation = assignmentConversation();
        when(conversationService.searchConversations(
                eq("user_1"),
                eq("essay"),
                eq(1),
                eq(20),
                eq(null),
                eq(null)))
                .thenReturn(new VerlaConversationListSlice(List.of(conversation), 1, 1, 20));
        when(dashboardStatusService.resolveAll(List.of(conversation)))
                .thenReturn(Map.of(conversation.getId(), "completed"));
        when(artifactMapper.selectByConversationIds(anyList()))
                .thenReturn(List.of(
                        artifact(conversation.getId(), conversation.getLastTurnId(), "slides_editor_json"),
                        artifact(conversation.getId(), conversation.getLastTurnId(), "document_md"),
                        artifact(conversation.getId(), conversation.getLastTurnId(), "code_python"),
                        artifact(conversation.getId(), conversation.getLastTurnId(), "slides_pptxgenjs")));

        Result<VerlaConversationPageVO> result = controller.search("user_1", "essay", 1, 20, null, null);

        assertThat(result.getData().getRecords()).hasSize(1);
        assertThat(result.getData().getRecords().get(0).getArtifactPreviewKinds())
                .containsExactly("document", "slides", "code");
        verify(artifactMapper).selectByConversationIds(List.of(101L));
    }

    @Test
    void mapperConversationLookup_includesArtifactBodyNeededForPreviewFiltering() {
        assertThat(VerlaArtifactMapper.SELECT_BY_CONVERSATION_IDS_COLUMNS)
                .isEqualTo(VerlaArtifactMapper.ARTIFACT_FULL_COLUMNS)
                .contains("body_or_ref");
    }

    /**
     * gate 的意义是「解析没结束就不发 turn」。所以这里断言的不是「调用过」，
     * 而是 awaitRelease 必须在 onUserMessage 之前发生 —— 顺序颠倒等于 gate 失效。
     */
    @Test
    void sendMessage_waitsForAttachmentParseBeforeDispatchingTheTurn() {
        SendMessageRequest req = new SendMessageRequest();
        req.setText("帮我写一篇作文");
        req.setAttachments(List.of(attachment("obj-1"), attachment("obj-2")));
        when(turnOrchestrator.onUserMessage(any()))
                .thenReturn(SendMessageResult.builder().turnId(9L).userMessageId(8L).build());

        Result<SendMessageResponseVO> result = controller.sendMessage("user_1", 101L, req);

        InOrder order = inOrder(attachmentParseGate, turnOrchestrator);
        order.verify(attachmentParseGate)
                .awaitRelease(isNull(), eq(List.of("obj-1", "obj-2")), eq(Duration.ofSeconds(180)));
        order.verify(turnOrchestrator).onUserMessage(any());
        // turnId 出参经过公开 id 编码（非裸数字），这里只断言链路把结果透传了。
        assertThat(result.getData().getTurnId()).isNotBlank();
    }

    /**
     * 前端允许重复带同一个 objectId（多选残留），也可能带空 objectId 的占位项。
     * 这些都必须在到达 gate 之前被清掉，否则 gate 会拿空 id 去查库。
     */
    @Test
    void sendMessage_dedupesObjectIdsAndDropsBlankOnesBeforeTheGate() {
        SendMessageRequest req = new SendMessageRequest();
        req.setText("继续");
        Map<String, Object> noId = new HashMap<>();
        req.setAttachments(Arrays.asList(
                attachment("obj-1"),
                attachment("obj-1"),
                attachment("  "),
                attachment("obj-2"),
                noId,
                null));
        when(turnOrchestrator.onUserMessage(any()))
                .thenReturn(SendMessageResult.builder().turnId(9L).userMessageId(8L).build());

        controller.sendMessage("user_1", 101L, req);

        verify(attachmentParseGate)
                .awaitRelease(isNull(), eq(List.of("obj-1", "obj-2")), eq(Duration.ofSeconds(180)));
    }

    @Test
    void sendMessage_withoutAttachmentsStillPassesThroughTheGateWithEmptyIds() {
        SendMessageRequest req = new SendMessageRequest();
        req.setText("纯文本");
        when(turnOrchestrator.onUserMessage(any()))
                .thenReturn(SendMessageResult.builder().turnId(9L).userMessageId(8L).build());

        controller.sendMessage("user_1", 101L, req);

        verify(attachmentParseGate).awaitRelease(isNull(), eq(List.of()), eq(Duration.ofSeconds(180)));
        verify(turnOrchestrator).onUserMessage(any());
    }

    /**
     * clarify/start 没有 request body，拿不到 objectId，只能靠会话最近一个 turn 去捞附件。
     * 这里断言它确实把最近 turn 的 id 交给了 gate —— 传 null 就等于这次 gate 白等。
     */
    @Test
    void startAssignmentClarify_waitsOnTheLatestTurnBeforeStartingClarify() {
        when(turnRepository.findRecentByConversation(101L, 1))
                .thenReturn(List.of(com.studyagent.service.domain.verla.VerlaTurn.builder().id(201L).build()));
        when(turnOrchestrator.startAssignmentClarifyFromLatestPlan("user_1", 101L))
                .thenReturn(SendMessageResult.builder().turnId(202L).build());

        controller.startAssignmentClarify("user_1", 101L);

        InOrder order = inOrder(attachmentParseGate, turnOrchestrator);
        order.verify(attachmentParseGate)
                .awaitRelease(eq(201L), eq(List.of()), eq(Duration.ofSeconds(180)));
        order.verify(turnOrchestrator).startAssignmentClarifyFromLatestPlan("user_1", 101L);
    }

    private static Map<String, Object> attachment(String objectId) {
        Map<String, Object> m = new HashMap<>();
        m.put("objectId", objectId);
        return m;
    }

    private static VerlaConversation assignmentConversation() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 3, 12, 0);
        return VerlaConversation.builder()
                .id(101L)
                .userId("user_1")
                .title("Essay assignment")
                .status(ConversationStatus.ACTIVE.getDbValue())
                .primaryIntent("ASSIGNMENT")
                .intentLifecycle(IntentLifecycle.COMMITTED.getDbValue())
                .turnCount(3)
                .lastTurnId(201L)
                .lastMessageAt(now)
                .createdAt(now.minusDays(1))
                .updatedAt(now)
                .build();
    }

    private static VerlaArtifactEntity artifact(Long conversationId, Long turnId, String kind) {
        return new VerlaArtifactEntity()
                .setConversationId(conversationId)
                .setTurnId(turnId)
                .setKind(kind)
                .setBodyOrRef("oss://bucket/artifact")
                .setStatus("READY");
    }
}
