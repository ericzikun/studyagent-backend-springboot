package com.studyagent.service.application.verla.handler;

import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.demo.DemoNoteService;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.state.SessionStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Notes 事件投影的契约：
 * <ol>
 *   <li>AI 写入（NOTE_ARTIFACT_COMMIT）落库并推进版本；空正文必须抛错（否则静默丢笔记）；</li>
 *   <li>回合终态（NOTE_TURN_COMPLETED）落助手消息并收口 session；</li>
 *   <li>失败 / 取消走 markFailed，且 session 状态推进是 best-effort。</li>
 * </ol>
 */
class NoteEventHandlerTest {

    private static final long SESSION_ID = 33L;
    private static final long VERLA_CONVERSATION_ID = 11L;

    private DemoNoteService demoNoteService;
    private VerlaSessionRepository sessionRepository;
    private NoteEventHandler handler;

    @BeforeEach
    void setUp() {
        demoNoteService = mock(DemoNoteService.class);
        sessionRepository = mock(VerlaSessionRepository.class);
        handler = new NoteEventHandler(demoNoteService, sessionRepository, new SessionStateMachine());
    }

    @Test
    void subscribes_only_to_events_that_need_java_side_actions() {
        assertEquals(
                java.util.Set.of(
                        VerlaAgentEventType.NOTE_PARSE_STARTED,
                        VerlaAgentEventType.NOTE_TITLE_RESOLVED,
                        VerlaAgentEventType.NOTE_ARTIFACT_COMMIT,
                        VerlaAgentEventType.NOTE_TURN_COMPLETED,
                        VerlaAgentEventType.NOTE_FAILED,
                        VerlaAgentEventType.NOTE_CANCELLED),
                handler.supportedTypes());
    }

    @Test
    void title_resolved_writes_the_generated_name_and_does_not_touch_session() {
        handler.handle(
                row(VerlaAgentEventType.NOTE_TITLE_RESOLVED),
                env(VerlaAgentEventType.NOTE_TITLE_RESOLVED, Map.of("title", "Business Ethics Memo Guidelines")));

        verify(demoNoteService).applyGeneratedTitle(VERLA_CONVERSATION_ID, "Business Ethics Memo Guidelines");
        // 起名与生成进度无关：不该推进 session 状态
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void parse_started_moves_session_to_running() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(dispatchingSession());

        handler.handle(row(VerlaAgentEventType.NOTE_PARSE_STARTED), env(VerlaAgentEventType.NOTE_PARSE_STARTED, Map.of()));

        ArgumentCaptor<VerlaSession> captor = ArgumentCaptor.forClass(VerlaSession.class);
        verify(sessionRepository).save(captor.capture());
        assertEquals("RUNNING", captor.getValue().getStatus());
    }

    @Test
    void artifact_commit_persists_content_and_reported_version() {
        handler.handle(
                row(VerlaAgentEventType.NOTE_ARTIFACT_COMMIT),
                env(VerlaAgentEventType.NOTE_ARTIFACT_COMMIT,
                        Map.of("contentMd", "# 主题\n正文", "versionNo", 4)));

        verify(demoNoteService).applyArtifactCommit(VERLA_CONVERSATION_ID, "# 主题\n正文", 4L);
        // 写入事件不推进 session 状态（回合终态才收口）
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void artifact_commit_without_content_fails_loudly() {
        // 空正文若被吞掉，会变成「前端看不到笔记、库里也没有」的静默丢失
        assertThrows(IllegalStateException.class, () -> handler.handle(
                row(VerlaAgentEventType.NOTE_ARTIFACT_COMMIT),
                env(VerlaAgentEventType.NOTE_ARTIFACT_COMMIT, Map.of("versionNo", 1))));
    }

    @Test
    void turn_completed_appends_assistant_message_and_succeeds_session() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(dispatchingSession());

        handler.handle(
                row(VerlaAgentEventType.NOTE_TURN_COMPLETED),
                env(VerlaAgentEventType.NOTE_TURN_COMPLETED,
                        Map.of("assistantText", "已更新笔记（版本 1）", "versionNo", 1, "commits", 1)));

        verify(demoNoteService).completeTurn(VERLA_CONVERSATION_ID, "已更新笔记（版本 1）");
        ArgumentCaptor<VerlaSession> captor = ArgumentCaptor.forClass(VerlaSession.class);
        verify(sessionRepository).save(captor.capture());
        assertEquals("SUCCEEDED", captor.getValue().getStatus());
    }

    @Test
    void failed_marks_conversation_and_session() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(dispatchingSession());

        handler.handle(
                row(VerlaAgentEventType.NOTE_FAILED),
                env(VerlaAgentEventType.NOTE_FAILED, Map.of("errorMessage", "boom")));

        verify(demoNoteService).markFailed(VERLA_CONVERSATION_ID);
        ArgumentCaptor<VerlaSession> captor = ArgumentCaptor.forClass(VerlaSession.class);
        verify(sessionRepository).save(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
    }

    @Test
    void cancelled_marks_conversation_without_failed_event_semantics() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(dispatchingSession());

        handler.handle(row(VerlaAgentEventType.NOTE_CANCELLED),
                env(VerlaAgentEventType.NOTE_CANCELLED, Map.of("reason", "user cancel")));

        verify(demoNoteService).markFailed(VERLA_CONVERSATION_ID);
        ArgumentCaptor<VerlaSession> captor = ArgumentCaptor.forClass(VerlaSession.class);
        verify(sessionRepository).save(captor.capture());
        assertEquals("CANCELLED", captor.getValue().getStatus());
    }

    @Test
    void session_state_advance_is_best_effort_and_never_breaks_drain() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(null);

        // session 丢了也不能让整条 inbox drain 中断（cursor 必须能继续推进）
        handler.handle(row(VerlaAgentEventType.NOTE_PARSE_STARTED),
                env(VerlaAgentEventType.NOTE_PARSE_STARTED, Map.of()));
    }

    @Test
    void artifact_commit_accepts_numeric_version_sent_as_string() {
        handler.handle(
                row(VerlaAgentEventType.NOTE_ARTIFACT_COMMIT),
                env(VerlaAgentEventType.NOTE_ARTIFACT_COMMIT, Map.of("contentMd", "x", "versionNo", "7")));

        verify(demoNoteService).applyArtifactCommit(VERLA_CONVERSATION_ID, "x", 7L);
    }

    private static VerlaEventInbox row(VerlaAgentEventType type) {
        return VerlaEventInbox.builder()
                .conversationId(VERLA_CONVERSATION_ID)
                .turnId(22L)
                .sessionId(SESSION_ID)
                .eventSeq(1L)
                .eventType(type.name())
                .build();
    }

    private static VerlaEventEnvelope env(VerlaAgentEventType type, Map<String, Object> payload) {
        return VerlaEventEnvelope.builder()
                .eventType(type.name())
                .payload(payload)
                .build();
    }

    private static VerlaSession dispatchingSession() {
        return VerlaSession.builder()
                .id(SESSION_ID)
                .conversationId(VERLA_CONVERSATION_ID)
                .turnId(22L)
                .status("DISPATCHING")
                .build();
    }
}
