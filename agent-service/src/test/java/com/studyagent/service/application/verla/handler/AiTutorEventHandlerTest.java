package com.studyagent.service.application.verla.handler;

import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.service.application.demo.DemoAiTutorService;
import com.studyagent.service.domain.demo.aitutor.AiTutorConversation;
import com.studyagent.service.domain.demo.aitutor.AiTutorDocument;
import com.studyagent.service.domain.demo.aitutor.repo.DemoAiTutorRepository;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.state.SessionStateMachine;
import com.studyagent.service.domain.verla.state.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 事件投影 handler 的契约测试。
 * <p>
 * 三类断言对应三个曾经真出过事的边界：
 * <ol>
 *   <li><b>订阅范围</b>：只订阅 5 个需要落库 / 推进状态的事件；把 6 个纯渲染事件也订进来，
 *       每个流式 chunk 都会多一次 DB 往返。</li>
 *   <li><b>终态事件必须真的投影</b>：与 {@code AITUTOR_TURN_COMPLETED} 对应的是「assistant 消息落库」，
 *       少了这步前端刷新即丢历史（Java 侧 valueOf 失败只 warn 一行，静默丢失极难排查）。</li>
 *   <li><b>异常策略</b>：session 状态推进是 best-effort（非法转换不能中断整条 drain）；
 *       但文档 / 消息落库失败必须外抛，让 inbox 标记 FAILED 且不推进 cursor，事件可重试。</li>
 * </ol>
 */
class AiTutorEventHandlerTest {

    private static final long CONVERSATION_ID = 11L;
    private static final long TURN_ID = 22L;
    private static final long SESSION_ID = 33L;
    private static final long DEMO_CONVERSATION_ID = 44L;

    private DemoAiTutorRepository demoRepository;
    private DemoAiTutorService demoAiTutorService;
    private VerlaSessionRepository sessionRepository;
    private SessionStateMachine sessionStateMachine;
    private AiTutorEventHandler handler;

    @BeforeEach
    void setUp() {
        demoRepository = mock(DemoAiTutorRepository.class);
        demoAiTutorService = mock(DemoAiTutorService.class);
        sessionRepository = mock(VerlaSessionRepository.class);
        sessionStateMachine = mock(SessionStateMachine.class);
        handler = new AiTutorEventHandler(
                demoRepository, demoAiTutorService, sessionRepository, sessionStateMachine);

        AiTutorConversation linked = new AiTutorConversation();
        linked.setId(DEMO_CONVERSATION_ID);
        linked.setVerlaConversationId(CONVERSATION_ID);
        when(demoRepository.findByVerlaConversationId(CONVERSATION_ID))
                .thenReturn(Optional.of(linked));
    }

    @Test
    void supported_types_cover_only_the_events_java_must_act_on() {
        Set<VerlaAgentEventType> supported = handler.supportedTypes();

        assertEquals(EnumSet.of(
                VerlaAgentEventType.AITUTOR_STARTED,
                VerlaAgentEventType.AITUTOR_ARTIFACT_COMMIT,
                VerlaAgentEventType.AITUTOR_TURN_COMPLETED,
                VerlaAgentEventType.AITUTOR_FAILED,
                VerlaAgentEventType.AITUTOR_CANCELLED), supported);
        // 纯前端渲染事件不订阅，避免每个流式 chunk 触发一次无意义的 DB 往返。
        for (String passthrough : new String[]{
                "AITUTOR_AGENT_SELECTED", "AITUTOR_AGENT_START", "AITUTOR_AGENT_END",
                "AITUTOR_CHAT_STREAM_CHUNK", "AITUTOR_ARTIFACT_BEGIN", "AITUTOR_ARTIFACT_DELTA"}) {
            assertFalse(supported.contains(VerlaAgentEventType.valueOf(passthrough)), passthrough);
        }
    }

    @Test
    void artifact_commit_persists_the_ai_version_through_the_service() {
        AiTutorDocument saved = new AiTutorDocument();
        saved.setConversationId(DEMO_CONVERSATION_ID);
        saved.setContentMd("# 引言\n新版");
        saved.setBaseVersion(3L);
        when(demoAiTutorService.saveAiUpdate(DEMO_CONVERSATION_ID, "# 引言\n新版")).thenReturn(saved);

        handler.handle(inbox("AITUTOR_ARTIFACT_COMMIT"), envelope("AITUTOR_ARTIFACT_COMMIT",
                Map.of("contentMd", "# 引言\n新版", "versionNo", 3)));

        // 落库交给 service，Java 是 versionNo 的唯一真相（payload 里 Python 回显的值只作对账日志）。
        verify(demoAiTutorService).saveAiUpdate(DEMO_CONVERSATION_ID, "# 引言\n新版");
        // commit 只落库，不推进 session 状态（状态由 STARTED / 终态事件负责）。
        verifyNoInteractions(sessionStateMachine);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void artifact_commit_without_content_is_skipped_not_written_as_empty_document() {
        handler.handle(inbox("AITUTOR_ARTIFACT_COMMIT"), envelope("AITUTOR_ARTIFACT_COMMIT", Map.of()));

        // 空 contentMd 会变成「把用户论文清空」，宁可跳过并留一行 warn。
        verifyNoInteractions(demoAiTutorService);
        verify(demoRepository, never()).findByVerlaConversationId(anyLong());
    }

    @Test
    void turn_completed_appends_the_assistant_message() {
        handler.handle(inbox("AITUTOR_TURN_COMPLETED"), envelope("AITUTOR_TURN_COMPLETED",
                Map.of("assistantText", "第二章已完成。", "versionNo", 3)));

        verify(demoAiTutorService).appendMessage(
                DEMO_CONVERSATION_ID, "assistant", "text", "第二章已完成。");
    }

    @Test
    void turn_completed_without_text_skips_the_message() {
        handler.handle(inbox("AITUTOR_TURN_COMPLETED"), envelope("AITUTOR_TURN_COMPLETED", Map.of()));
        handler.handle(inbox("AITUTOR_TURN_COMPLETED"),
                envelope("AITUTOR_TURN_COMPLETED", Map.of("assistantText", "   ")));

        verify(demoAiTutorService, never()).appendMessage(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void started_advances_the_session_without_touching_demo_rows() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(session(SessionStatus.DISPATCHING.name()));
        when(sessionStateMachine.next(eq(SessionStatus.DISPATCHING), any()))
                .thenReturn(SessionStatus.RUNNING);

        handler.handle(inbox("AITUTOR_STARTED"), envelope("AITUTOR_STARTED", Map.of()));

        verify(sessionRepository).save(any(VerlaSession.class));
        verifyNoInteractions(demoAiTutorService);
    }

    @Test
    void terminal_events_advance_the_session_to_its_terminal_status() {
        VerlaSession running = session(SessionStatus.RUNNING.name());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(running);
        when(sessionStateMachine.next(eq(SessionStatus.RUNNING), any()))
                .thenReturn(SessionStatus.SUCCEEDED);
        handler.handle(inbox("AITUTOR_TURN_COMPLETED"),
                envelope("AITUTOR_TURN_COMPLETED", Map.of("assistantText", "done")));
        assertEquals(SessionStatus.SUCCEEDED.name(), running.getStatus());
        assertTrue(running.getEndedAt() != null, "终态必须写 ended_at");

        VerlaSession failing = session(SessionStatus.RUNNING.name());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(failing);
        when(sessionStateMachine.next(eq(SessionStatus.RUNNING), any()))
                .thenReturn(SessionStatus.FAILED);
        handler.handle(inbox("AITUTOR_FAILED"),
                envelope("AITUTOR_FAILED", Map.of("errorMessage", "主循环超时")));
        assertEquals(SessionStatus.FAILED.name(), failing.getStatus());
    }

    @Test
    void session_advance_failure_is_swallowed_so_the_inbox_drain_survives() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(session(SessionStatus.CREATED.name()));
        when(sessionStateMachine.next(any(), any()))
                .thenThrow(new IllegalStateException("Invalid session transition"));

        // 非法转换（例如 chunk 早于 STARTED 到达）不该中断整条 inbox drain。
        assertDoesNotThrow(() ->
                handler.handle(inbox("AITUTOR_FAILED"), envelope("AITUTOR_FAILED", Map.of())));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void missing_session_is_swallowed_too() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(null);

        assertDoesNotThrow(() ->
                handler.handle(inbox("AITUTOR_STARTED"), envelope("AITUTOR_STARTED", Map.of())));
    }

    @Test
    void unlinked_demo_conversation_fails_loudly_so_the_event_can_be_retried() {
        when(demoRepository.findByVerlaConversationId(CONVERSATION_ID)).thenReturn(Optional.empty());

        // 吞掉异常会让 inbox 标 PROCESSED 并推 SSE：前端看到内容却从未持久化，刷新即丢。
        assertThrows(IllegalStateException.class, () ->
                handler.handle(inbox("AITUTOR_ARTIFACT_COMMIT"),
                        envelope("AITUTOR_ARTIFACT_COMMIT", Map.of("contentMd", "# 正文"))));
        verifyNoInteractions(demoAiTutorService);
    }

    @Test
    void persistence_failure_propagates_to_roll_back_the_inbox_row() {
        when(demoRepository.findByVerlaConversationId(CONVERSATION_ID))
                .thenReturn(Optional.of(linkedConversation()));
        when(demoAiTutorService.saveAiUpdate(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () ->
                handler.handle(inbox("AITUTOR_ARTIFACT_COMMIT"),
                        envelope("AITUTOR_ARTIFACT_COMMIT", Map.of("contentMd", "# 正文"))));
    }

    @Test
    void unsupported_event_type_is_ignored_without_side_effects() {
        handler.handle(inbox("AITUTOR_ARTIFACT_DELTA"),
                envelope("AITUTOR_ARTIFACT_DELTA", Map.of("contentMd", "# 半成品")));

        verifyNoInteractions(demoAiTutorService, sessionRepository);
    }

    private static AiTutorConversation linkedConversation() {
        AiTutorConversation conversation = new AiTutorConversation();
        conversation.setId(DEMO_CONVERSATION_ID);
        conversation.setVerlaConversationId(CONVERSATION_ID);
        return conversation;
    }

    private static VerlaEventInbox inbox(String eventType) {
        return VerlaEventInbox.builder()
                .conversationId(CONVERSATION_ID)
                .turnId(TURN_ID)
                .sessionId(SESSION_ID)
                .eventSeq(1L)
                .eventType(eventType)
                .build();
    }

    private static VerlaEventEnvelope envelope(String eventType, Map<String, Object> payload) {
        return VerlaEventEnvelope.builder()
                .eventType(eventType)
                .payload(new LinkedHashMap<>(payload))
                .build();
    }

    private static VerlaSession session(String status) {
        return VerlaSession.builder()
                .id(SESSION_ID)
                .conversationId(CONVERSATION_ID)
                .turnId(TURN_ID)
                .status(status)
                .build();
    }
}
