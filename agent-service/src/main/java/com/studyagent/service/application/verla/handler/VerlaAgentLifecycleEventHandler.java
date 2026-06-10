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
            VerlaAgentEventType.ASSIGNMENT_INIT_STARTED,
            VerlaAgentEventType.ASSIGNMENT_INIT_COMPLETED,
            VerlaAgentEventType.ASSIGNMENT_INIT_FAILED,
            VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_STARTED,
            VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED,
            VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_FAILED,
            VerlaAgentEventType.ASSIGNMENT_CLARIFY_FORM_READY,
            VerlaAgentEventType.ASSIGNMENT_CLARIFY_STARTED,
            VerlaAgentEventType.ASSIGNMENT_CLARIFY_COMPLETED,
            VerlaAgentEventType.ASSIGNMENT_CLARIFY_FAILED,
            VerlaAgentEventType.ASSIGNMENT_CLARIFY_CANCELLED,
            VerlaAgentEventType.ASSIGNMENT_STARTED,
            VerlaAgentEventType.ASSIGNMENT_COMPLETED,
            VerlaAgentEventType.ASSIGNMENT_FAILED,
            VerlaAgentEventType.ASSIGNMENT_CANCELLED,
            VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
            VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_COMPLETED,
            VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_FAILED,
            VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_CANCELLED,
            VerlaAgentEventType.FILE_CHAT_STARTED,
            VerlaAgentEventType.FILE_CHAT_COMPLETED,
            VerlaAgentEventType.FILE_CHAT_FAILED,
            VerlaAgentEventType.FILE_CHAT_CANCELLED,
            VerlaAgentEventType.MATERIALS_STARTED,
            VerlaAgentEventType.MATERIALS_COMPLETED,
            VerlaAgentEventType.AI_DETECTION_COMPLETED,
            VerlaAgentEventType.AI_HUMANIZER_COMPLETED);

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
            case AGENT_STARTED, ASSIGNMENT_INIT_STARTED, ASSIGNMENT_DEEP_UNDERSTANDING_STARTED,
                    ASSIGNMENT_CLARIFY_STARTED, ASSIGNMENT_STARTED, MATERIALS_STARTED,
                    ASSIGNMENT_AGENT_FLOW_STARTED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAgentStarted(sessionId);
            }
            case FILE_CHAT_STARTED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onFileChatStarted(sessionId);
            }
            case ASSIGNMENT_INIT_COMPLETED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAssignmentInitCompleted(sessionId, payload);
            }
            case ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAssignmentDeepUnderstandingCompleted(sessionId, payload);
            }
            case ASSIGNMENT_CLARIFY_FORM_READY -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAssignmentClarifyFormReady(sessionId, payload);
            }
            case ASSIGNMENT_CLARIFY_COMPLETED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAssignmentClarifyCompleted(sessionId, payload);
            }
            case AGENT_COMPLETED, MATERIALS_COMPLETED,
                    AI_DETECTION_COMPLETED,
                    AI_HUMANIZER_COMPLETED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAgentCompleted(sessionId, payload);
            }
            case ASSIGNMENT_COMPLETED, ASSIGNMENT_AGENT_FLOW_COMPLETED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAssignmentCompleted(sessionId, payload);
            }
            case FILE_CHAT_COMPLETED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onFileChatCompleted(sessionId, payload);
            }
            case AGENT_FAILED, ASSIGNMENT_INIT_FAILED, ASSIGNMENT_DEEP_UNDERSTANDING_FAILED,
                    ASSIGNMENT_CLARIFY_FAILED, AI_DETECTION_FAILED, AI_HUMANIZER_FAILED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAgentFailed(sessionId, payload);
            }
            case ASSIGNMENT_FAILED, ASSIGNMENT_AGENT_FLOW_FAILED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAssignmentFailed(sessionId, payload);
            }
            case FILE_CHAT_FAILED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onFileChatFailed(sessionId, payload);
            }
            case AGENT_CANCELLED, ASSIGNMENT_CLARIFY_CANCELLED, ASSIGNMENT_CANCELLED,
                    ASSIGNMENT_AGENT_FLOW_CANCELLED, AI_DETECTION_CANCELLED,
                    AI_HUMANIZER_CANCELLED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onAgentCancelled(sessionId);
            }
            case FILE_CHAT_CANCELLED -> {
                log.info("[Verla/agent] {} sessionId={}", type, sessionId);
                orchestrator.onFileChatCancelled(sessionId);
            }
            default -> log.warn("[Verla/agent] unexpected event {}", type);
        }
    }
}
