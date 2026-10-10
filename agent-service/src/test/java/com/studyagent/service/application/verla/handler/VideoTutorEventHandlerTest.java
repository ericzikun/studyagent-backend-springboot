package com.studyagent.service.application.verla.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Video Tutor handler 的行为断言。
 * <p>
 * 最重要的一条是 {@link #supportedTypes_shouldBeExactlyTheFiveProjectedEvents()}：
 * {@code VerlaEventHandlerDispatcher} 对未注册 eventType 是<b>静默 processed</b>（不抛错、
 * 只打 debug 日志、cursor 继续推进），漏注册的唯一症状是"板子和消息没了但一切都绿"。
 * 只有内容断言能兜住它。
 */
@ExtendWith(MockitoExtension.class)
class VideoTutorEventHandlerTest {

    private static final long SESSION_ID = 9101L;
    private static final long CONVERSATION_ID = 11L;
    private static final long TURN_ID = 22L;

    @Mock
    private VerlaTurnOrchestrator orchestrator;
    @Mock
    private VerlaArtifactRepository artifactRepository;
    @Mock
    private VerlaMessageRepository messageRepository;
    @Mock
    private VerlaConversationRepository conversationRepository;

    private VideoTutorEventHandler handler() {
        return new VideoTutorEventHandler(
                orchestrator, artifactRepository, messageRepository,
                conversationRepository, new ObjectMapper());
    }

    private static VerlaEventInbox row(VerlaAgentEventType type) {
        return VerlaEventInbox.builder()
                .sessionId(SESSION_ID)
                .conversationId(CONVERSATION_ID)
                .turnId(TURN_ID)
                .eventSeq(3L)
                .eventType(type.name())
                .status(VerlaEventInbox.STATUS_READY)
                .receivedAt(LocalDateTime.now())
                .build();
    }

    private static VerlaEventEnvelope envelope(VerlaAgentEventType type, Map<String, Object> payload) {
        return VerlaEventEnvelope.builder()
                .messageId("evt_" + SESSION_ID)
                .correlationId("conv:" + CONVERSATION_ID + ":turn:" + TURN_ID + ":sess:" + SESSION_ID)
                .eventType(type.name())
                .payload(payload)
                .build();
    }

    @Test
    void supportedTypes_shouldBeExactlyTheFiveProjectedEvents() {
        assertThat(handler().supportedTypes()).containsExactlyInAnyOrder(
                VerlaAgentEventType.VIDEO_TUTOR_STARTED,
                VerlaAgentEventType.VIDEO_TUTOR_BOARD_COMMIT,
                VerlaAgentEventType.VIDEO_TUTOR_TURN_COMPLETED,
                VerlaAgentEventType.VIDEO_TUTOR_FAILED,
                VerlaAgentEventType.VIDEO_TUTOR_CANCELLED);

        // 这两个是纯渲染帧。注意不注册 ≠ 不落 inbox（SSE 由 drain 驱动，它们照样占一行并参与保序）；
        // 不注册是为了不给它们做任何投影副作用
        assertThat(handler().supportedTypes())
                .doesNotContain(VerlaAgentEventType.VIDEO_TUTOR_BOARD_OPS)
                .doesNotContain(VerlaAgentEventType.VIDEO_TUTOR_CHAT_STREAM_CHUNK);
    }

    @Test
    void boardCommit_shouldUpsertTheConversationLevelSingleRow() {
        when(artifactRepository.findByUid("board_" + CONVERSATION_ID)).thenReturn(null);
        when(artifactRepository.upsertByUid(any(VerlaArtifact.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> board = Map.of("ref", "b2", "op", "eq", "latex", "y=a(x-h)^2+k");
        handler().handle(row(VerlaAgentEventType.VIDEO_TUTOR_BOARD_COMMIT),
                envelope(VerlaAgentEventType.VIDEO_TUTOR_BOARD_COMMIT, Map.of(
                        "board", List.of(board),
                        "refSeq", 2,
                        "boardVersion", 1,
                        "meta", Map.of("pacing", Map.of("breaks", 0), "topic", "二次函数的顶点式"))));

        ArgumentCaptor<VerlaArtifact> captor = ArgumentCaptor.forClass(VerlaArtifact.class);
        verify(artifactRepository).upsertByUid(captor.capture());
        VerlaArtifact saved = captor.getValue();

        assertThat(saved.getArtifactUid()).isEqualTo("board_" + CONVERSATION_ID);
        assertThat(saved.getKind()).isEqualTo(VideoTutorEventHandler.BOARD_ARTIFACT_KIND);
        assertThat(saved.getMime()).isEqualTo("application/json");
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo("READY");
        assertThat(saved.getBodyOrRef()).contains("y=a(x-h)^2+k");
        assertThat(saved.getSizeBytes()).isPositive();
        assertThat(saved.getMetaJson()).contains("breaks").contains("二次函数的顶点式");
        // 白板变了必须 bump 会话版本，否则上下文 cache 会拿到旧快照
        verify(conversationRepository).incrementVersion(CONVERSATION_ID);
    }

    @Test
    void boardCommit_shouldTakeTheVersionFromJavaNotThePythonEcho() {
        // 已有 version=7，而 Python 回显 99 —— 两个都不能采信，真相是 Java 自己 +1
        when(artifactRepository.findByUid(anyString()))
                .thenReturn(VerlaArtifact.builder().version(7).build());
        when(artifactRepository.upsertByUid(any(VerlaArtifact.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        handler().handle(row(VerlaAgentEventType.VIDEO_TUTOR_BOARD_COMMIT),
                envelope(VerlaAgentEventType.VIDEO_TUTOR_BOARD_COMMIT,
                        Map.of("board", List.of(), "boardVersion", 99)));

        ArgumentCaptor<VerlaArtifact> captor = ArgumentCaptor.forClass(VerlaArtifact.class);
        verify(artifactRepository).upsertByUid(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(8);
    }

    @Test
    void boardCommit_withoutBoardKeyShouldNotTouchAnyTable() {
        handler().handle(row(VerlaAgentEventType.VIDEO_TUTOR_BOARD_COMMIT),
                envelope(VerlaAgentEventType.VIDEO_TUTOR_BOARD_COMMIT, Map.of("refSeq", 2)));

        verifyNoInteractions(artifactRepository);
        verifyNoInteractions(conversationRepository);
    }

    @Test
    void boardCommit_persistFailureMustPropagateSoInboxMarksFailed() {
        // 吞掉异常 = 这行 inbox 被标 processed、板子永久停在旧状态且没人重试
        when(artifactRepository.findByUid(anyString())).thenReturn(null);
        when(artifactRepository.upsertByUid(any(VerlaArtifact.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> handler().handle(
                row(VerlaAgentEventType.VIDEO_TUTOR_BOARD_COMMIT),
                envelope(VerlaAgentEventType.VIDEO_TUTOR_BOARD_COMMIT,
                        Map.of("board", List.of(Map.of("op", "line", "text", "x"))))))
                .isInstanceOf(IllegalStateException.class);

        verify(conversationRepository, never()).incrementVersion(anyLong());
    }

    @Test
    void turnCompleted_shouldCompleteTheSessionAndPersistOneAssistantMessage() {
        when(messageRepository.findByTurnRoleScene(TURN_ID, "assistant",
                VideoTutorEventHandler.SCENE_VIDEO_TUTOR)).thenReturn(null);

        handler().handle(row(VerlaAgentEventType.VIDEO_TUTOR_TURN_COMPLETED),
                envelope(VerlaAgentEventType.VIDEO_TUTOR_TURN_COMPLETED, Map.of(
                        "finalText", "那 h 变大时，顶点往哪边走？",
                        "steps", 3)));

        verify(orchestrator).onAgentCompleted(eq(SESSION_ID), any());
        ArgumentCaptor<VerlaMessage> captor = ArgumentCaptor.forClass(VerlaMessage.class);
        verify(messageRepository).save(captor.capture());
        VerlaMessage msg = captor.getValue();
        assertThat(msg.getRole()).isEqualTo("assistant");
        assertThat(msg.getScene()).isEqualTo(VideoTutorEventHandler.SCENE_VIDEO_TUTOR);
        assertThat(msg.getTurnId()).isEqualTo(TURN_ID);
        assertThat(msg.getTextContent()).isEqualTo("那 h 变大时，顶点往哪边走？");

        // 终态 payload 里没有 board；这里再 upsert 一次会白写一行并多 bump 一个版本
        verifyNoInteractions(artifactRepository);
    }

    @Test
    void turnCompleted_shouldNotWriteADuplicateAssistantMessageOnRedelivery() {
        when(messageRepository.findByTurnRoleScene(TURN_ID, "assistant",
                VideoTutorEventHandler.SCENE_VIDEO_TUTOR))
                .thenReturn(VerlaMessage.builder().id(1L).textContent("已存在").build());

        handler().handle(row(VerlaAgentEventType.VIDEO_TUTOR_TURN_COMPLETED),
                envelope(VerlaAgentEventType.VIDEO_TUTOR_TURN_COMPLETED, Map.of("finalText", "重复投递")));

        verify(messageRepository, never()).save(any(VerlaMessage.class));
    }

    @Test
    void turnCompleted_withBlankFinalTextShouldStillCloseTheSession() {
        handler().handle(row(VerlaAgentEventType.VIDEO_TUTOR_TURN_COMPLETED),
                envelope(VerlaAgentEventType.VIDEO_TUTOR_TURN_COMPLETED, Map.of("finalText", "  ")));

        verify(orchestrator).onAgentCompleted(eq(SESSION_ID), any());
        verifyNoInteractions(messageRepository);
    }

    @Test
    void lifecycleEvents_shouldDelegateToTheGenericStateMachineCalls() {
        handler().handle(row(VerlaAgentEventType.VIDEO_TUTOR_STARTED),
                envelope(VerlaAgentEventType.VIDEO_TUTOR_STARTED, Map.of("topic", "顶点式")));
        handler().handle(row(VerlaAgentEventType.VIDEO_TUTOR_FAILED),
                envelope(VerlaAgentEventType.VIDEO_TUTOR_FAILED, Map.of("errorMessage", "boom")));
        handler().handle(row(VerlaAgentEventType.VIDEO_TUTOR_CANCELLED),
                envelope(VerlaAgentEventType.VIDEO_TUTOR_CANCELLED, Map.of("reason", "user")));

        // 白板轮次不改变 session/turn 状态机的形状，直接用通用委派：
        // onAgentStarted 只认 DISPATCHING→RUNNING，与 kind 无关，无需为每个新域加一个 one-line 方法
        verify(orchestrator).onAgentStarted(SESSION_ID);
        verify(orchestrator).onAgentFailed(eq(SESSION_ID), any());
        verify(orchestrator).onAgentCancelled(SESSION_ID);
        verifyNoInteractions(artifactRepository);
    }

    @Test
    void boardArtifactUid_shouldStayConversationScopedForTheSnapshotReader() {
        assertThat(VideoTutorEventHandler.boardArtifactUid(2001L)).isEqualTo("board_2001");
    }
}
