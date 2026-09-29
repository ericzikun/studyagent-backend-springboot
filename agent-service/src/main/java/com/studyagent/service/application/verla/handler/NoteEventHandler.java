package com.studyagent.service.application.verla.handler;

import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.service.application.demo.DemoNoteService;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.state.SessionEvent;
import com.studyagent.service.domain.verla.state.SessionStateMachine;
import com.studyagent.service.domain.verla.state.SessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Notes demo 的事件投影：把主线通道回流的 {@code NOTE_*} 事件落进 demo 自己的会话行，
 * 并推进 {@code verla_sessions.status}。
 *
 * <p><b>只订阅需要 Java 侧动作的 4 个事件。</b>{@code NOTE_PARSE_COMPLETED /
 * NOTE_SUMMARIZE_STARTED / NOTE_STREAM_CHUNK} 是纯前端渲染事件，由
 * {@link com.studyagent.service.application.verla.VerlaInboxService} 无条件推给 SSE，
 * 不需要 handler；不订阅可避免每个流式 chunk 都触发一次无意义的 DB 往返。
 *
 * <p><b>不碰 {@code verla_turns} 状态机、不碰 quota、不回调 orchestrator</b> ——
 * demo 会话的 {@code primary_intent='NOTE_MAKER'} 不属于任何商业化功能点，
 * 刻意不复用 {@link VerlaAgentLifecycleEventHandler}（后者耦合 assignment 状态机与配额退款）。
 *
 * <p><b>异常策略</b>：session 状态推进是 best-effort（失败只 warn，且非法转换不该中断整条
 * inbox drain）；笔记正文落库失败刻意向上抛 —— handler 与 inbox 共享事务，抛出会让该行
 * 标记 FAILED 而不推进 cursor，事件可重试；吞掉会造成「前端看到笔记、库里没有」的静默丢失。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoteEventHandler implements VerlaEventHandler {

    private static final Set<VerlaAgentEventType> SUPPORTED = EnumSet.of(
            VerlaAgentEventType.NOTE_PARSE_STARTED,
            VerlaAgentEventType.NOTE_COMPLETED,
            VerlaAgentEventType.NOTE_FAILED,
            VerlaAgentEventType.NOTE_CANCELLED);

    private final DemoNoteService demoNoteService;
    private final VerlaSessionRepository sessionRepository;
    private final SessionStateMachine sessionStateMachine;

    @Override
    public Set<VerlaAgentEventType> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public void handle(VerlaEventInbox row, VerlaEventEnvelope env) {
        VerlaAgentEventType type = VerlaAgentEventType.valueOf(row.getEventType());
        Map<String, Object> payload = env == null || env.getPayload() == null
                ? Map.of() : env.getPayload();

        switch (type) {
            case NOTE_PARSE_STARTED -> advanceSession(row.getSessionId(), SessionEvent.AGENT_STARTED);
            case NOTE_COMPLETED -> {
                saveNote(row, payload);
                advanceSession(row.getSessionId(), SessionEvent.AGENT_COMPLETED);
            }
            case NOTE_FAILED -> {
                log.warn("[Notes] generation failed sessionId={} error={}",
                        row.getSessionId(), payload.get("errorMessage"));
                demoNoteService.markFailed(row.getConversationId());
                advanceSession(row.getSessionId(), SessionEvent.AGENT_FAILED);
            }
            case NOTE_CANCELLED -> {
                log.info("[Notes] generation cancelled sessionId={} reason={}",
                        row.getSessionId(), payload.get("reason"));
                // 取消按「未完成」收口：前端据此提示可重试，不落 failed 事件语义
                demoNoteService.markFailed(row.getConversationId());
                advanceSession(row.getSessionId(), SessionEvent.AGENT_CANCELLED);
            }
            default -> log.debug("[Notes] ignored event {} sessionId={}", type, row.getSessionId());
        }
    }

    private void saveNote(VerlaEventInbox row, Map<String, Object> payload) {
        String noteMd = asString(payload.get("noteMarkdown"));
        if (noteMd == null || noteMd.isBlank()) {
            throw new IllegalStateException(
                    "NOTE_COMPLETED without noteMarkdown, sessionId=" + row.getSessionId());
        }
        demoNoteService.applyGeneratedNote(row.getConversationId(), noteMd, asString(payload.get("title")));
        log.info("[Notes] note saved convId={} chars={}", row.getConversationId(), noteMd.length());
    }

    /** best-effort 推进 session 状态；失败只记录，绝不中断 inbox drain。 */
    private void advanceSession(Long sessionId, SessionEvent event) {
        try {
            VerlaSession session = sessionRepository.findById(sessionId);
            if (session == null) {
                log.warn("[Notes] session not found: {}", sessionId);
                return;
            }
            SessionStatus current = SessionStatus.valueOf(session.getStatus());
            SessionStatus next = sessionStateMachine.next(current, event);
            if (next == current) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            session.setStatus(next.name());
            session.setLastProgressAt(now);
            session.setUpdatedAt(now);
            if (next.isTerminal()) {
                session.setEndedAt(now);
            }
            sessionRepository.save(session);
        } catch (RuntimeException ex) {
            log.warn("[Notes] session state advance skipped: sessionId={} event={} reason={}",
                    sessionId, event, ex.getMessage());
        }
    }

    private static String asString(Object raw) {
        return raw == null ? null : raw.toString();
    }
}
