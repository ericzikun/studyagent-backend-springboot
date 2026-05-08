package com.studyagent.service.application.verla.handler;

import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Verla Agent 生命周期事件 handler（PR-13 配套）
 * <p>
 * 仅处理 session 级生命周期事件，让 session/turn 状态机正确推进；
 * 流式 STREAM_CHUNK / STEP_* / ARTIFACT_PATCH 等增量事件由 PR-14 的
 * VerlaAgentEventHandler 接管，本 handler 不重复注册。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaAgentLifecycleEventHandler implements VerlaEventHandler {

    private static final Set<VerlaAgentEventType> SUPPORTED = EnumSet.of(
            VerlaAgentEventType.AGENT_STARTED,
            VerlaAgentEventType.AGENT_COMPLETED,
            VerlaAgentEventType.AGENT_FAILED,
            VerlaAgentEventType.AGENT_CANCELLED,
            VerlaAgentEventType.ASSIGNMENT_STARTED,
            VerlaAgentEventType.ASSIGNMENT_COMPLETED,
            VerlaAgentEventType.ASSIGNMENT_FAILED,
            VerlaAgentEventType.ASSIGNMENT_CANCELLED,
            VerlaAgentEventType.MATERIALS_STARTED,
            VerlaAgentEventType.MATERIALS_COMPLETED);

    private final VerlaTurnOrchestrator orchestrator;

    @Override
    public Set<VerlaAgentEventType> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public void handle(VerlaEventInbox row, VerlaEventEnvelope env) {
        VerlaAgentEventType type = VerlaAgentEventType.valueOf(row.getEventType());
        Long sessionId = row.getSessionId();
        Map<String, Object> payload = env == null || env.getPayload() == null
                ? Map.of() : env.getPayload();

        switch (type) {
            case AGENT_STARTED, ASSIGNMENT_STARTED, MATERIALS_STARTED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAgentStarted(sessionId);
            }
            case AGENT_COMPLETED, ASSIGNMENT_COMPLETED, MATERIALS_COMPLETED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAgentCompleted(sessionId, payload);
            }
            case AGENT_FAILED, ASSIGNMENT_FAILED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAgentFailed(sessionId, payload);
            }
            case AGENT_CANCELLED, ASSIGNMENT_CANCELLED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAgentCancelled(sessionId);
            }
            default -> log.warn("[Verla/agent] unexpected event {}", type);
        }
    }
}
