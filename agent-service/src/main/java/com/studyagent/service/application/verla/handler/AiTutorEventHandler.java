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
import com.studyagent.service.domain.verla.state.SessionEvent;
import com.studyagent.service.domain.verla.state.SessionStateMachine;
import com.studyagent.service.domain.verla.state.SessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AI Tutor demo 的事件投影：把主线通道回流的 {@code AITUTOR_*} 事件落进 demo 自己的表
 * （文档 / 版本 / 消息），并推进 {@code verla_sessions.status}。
 *
 * <p><b>只订阅需要 Java 侧动作的 5 个事件。</b>其余 6 个
 * （{@code AGENT_SELECTED / AGENT_START / AGENT_END / CHAT_STREAM_CHUNK /
 * ARTIFACT_BEGIN / ARTIFACT_DELTA}）是纯前端渲染事件，由
 * {@link com.studyagent.service.application.verla.VerlaInboxService} 无条件推给 SSE，
 * 不需要 handler；不订阅可避免每个流式 chunk 都触发一次无意义的 DB 往返。
 *
 * <p><b>不碰 {@code verla_turns} 状态机、不碰 quota、不回调 orchestrator</b>——
 * demo 会话的 {@code primary_intent='AI_TUTOR'} 不属于任何商业化功能点，
 * 也刻意不复用 {@code VerlaAgentLifecycleEventHandler}（后者耦合 assignment 状态机与配额退款）。
 *
 * <p><b>异常策略分两类</b>：
 * <ul>
 *   <li>session 状态推进是 best-effort，失败只 warn——它不影响用户可见产物，
 *       且非法转换（如 chunk 早于 STARTED 到达）不该中断整条 inbox drain；</li>
 *   <li>文档 / 消息落库失败<b>刻意向上抛</b>：handler 与 inbox 共享事务，抛出会让
 *       VerlaInboxService 把该行标记 FAILED 而不推进 cursor，事件可重试。
 *       若在此吞掉异常，inbox 会标记 PROCESSED 并推 SSE，前端看到内容却从未持久化，
 *       刷新即丢失——静默数据丢失比失败重试更糟。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiTutorEventHandler implements VerlaEventHandler {

    private static final Set<VerlaAgentEventType> SUPPORTED = EnumSet.of(
            VerlaAgentEventType.AITUTOR_STARTED,
            VerlaAgentEventType.AITUTOR_ARTIFACT_COMMIT,
            VerlaAgentEventType.AITUTOR_TURN_COMPLETED,
            VerlaAgentEventType.AITUTOR_FAILED,
            VerlaAgentEventType.AITUTOR_CANCELLED);

    private final DemoAiTutorRepository demoRepository;
    private final DemoAiTutorService demoAiTutorService;
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
            case AITUTOR_STARTED -> advanceSession(row.getSessionId(), SessionEvent.AGENT_STARTED);
            case AITUTOR_ARTIFACT_COMMIT -> commitDocument(row, payload);
            case AITUTOR_TURN_COMPLETED -> {
                appendAssistantMessage(row, payload);
                advanceSession(row.getSessionId(), SessionEvent.AGENT_COMPLETED);
            }
            case AITUTOR_FAILED -> {
                log.warn("[AI-Tutor] turn failed sessionId={} error={}",
                        row.getSessionId(), payload.get("errorMessage"));
                advanceSession(row.getSessionId(), SessionEvent.AGENT_FAILED);
            }
            case AITUTOR_CANCELLED -> {
                log.info("[AI-Tutor] turn cancelled sessionId={} reason={}",
                        row.getSessionId(), payload.get("reason"));
                advanceSession(row.getSessionId(), SessionEvent.AGENT_CANCELLED);
            }
            default -> log.debug("[AI-Tutor] ignored event {} sessionId={}", type, row.getSessionId());
        }
    }

    /**
     * AI 产物落库。Java 是 versionNo 的唯一真相：{@code saveAiUpdate} 永远基于库中最新基线
     * （含用户在流式期间抢存的手改版）自增，payload 里 Python 回显的版本号只作对账日志。
     */
    private void commitDocument(VerlaEventInbox row, Map<String, Object> payload) {
        String contentMd = asString(payload.get("contentMd"));
        if (contentMd == null) {
            log.warn("[AI-Tutor] AITUTOR_ARTIFACT_COMMIT without contentMd, sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }
        Long demoConversationId = requireDemoConversationId(row);
        AiTutorDocument saved = demoAiTutorService.saveAiUpdate(demoConversationId, contentMd);
        log.info("[AI-Tutor] artifact committed demoConvId={} versionNo={} (python echoed {})",
                demoConversationId, saved.getBaseVersion(), payload.get("versionNo"));
    }

    private void appendAssistantMessage(VerlaEventInbox row, Map<String, Object> payload) {
        String text = asString(payload.get("assistantText"));
        if (text == null || text.isBlank()) {
            log.warn("[AI-Tutor] AITUTOR_TURN_COMPLETED without assistantText, sessionId={}", row.getSessionId());
            return;
        }
        Long demoConversationId = requireDemoConversationId(row);
        demoAiTutorService.appendMessage(demoConversationId, "assistant", "text", text);
    }

    /**
     * 由主线会话 id 反查 demo 会话（sql/083 的唯一键），不依赖 Python 在 payload 里回显。
     * 查不到说明 demo 行与主线行脱链，属于必须暴露的配置错误。
     */
    private Long requireDemoConversationId(VerlaEventInbox row) {
        Optional<AiTutorConversation> demo = demoRepository.findByVerlaConversationId(row.getConversationId());
        if (demo.isEmpty()) {
            throw new IllegalStateException(
                    "AI Tutor demo conversation not linked to verla conversation " + row.getConversationId());
        }
        return demo.get().getId();
    }

    /** best-effort 推进 session 状态；失败只记录，绝不中断 inbox drain。 */
    private void advanceSession(Long sessionId, SessionEvent event) {
        try {
            VerlaSession session = sessionRepository.findById(sessionId);
            if (session == null) {
                log.warn("[AI-Tutor] session not found: {}", sessionId);
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
            log.warn("[AI-Tutor] session state advance skipped: sessionId={} event={} reason={}",
                    sessionId, event, ex.getMessage());
        }
    }

    private static String asString(Object raw) {
        return raw == null ? null : raw.toString();
    }
}
