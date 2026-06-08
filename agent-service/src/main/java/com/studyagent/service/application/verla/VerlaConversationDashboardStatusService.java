package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.service.domain.verla.VerlaClarifyForm;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaClarifyFormRepository;
import com.studyagent.service.domain.verla.repo.VerlaEventInboxRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import com.studyagent.service.domain.verla.state.IntentLifecycle;
import com.studyagent.service.domain.verla.state.SessionStatus;
import com.studyagent.service.domain.verla.state.TurnStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the Dashboard-facing task status for Verla conversation history.
 *
 * This service does not mutate conversation lifecycle state. It reads the
 * conversation, latest processed Verla event type, and turn/session fallback
 * signals to produce a lightweight UI status for history cards while preserving
 * {@code conversation.status} for archive/delete/writeability semantics.
 */
@Service
@RequiredArgsConstructor
public class VerlaConversationDashboardStatusService {

    public static final String STATUS_PROGRESSING = "progressing";
    public static final String STATUS_NEEDS_CHOICE = "needs-choice";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    private final VerlaTurnRepository turnRepository;
    private final VerlaSessionRepository sessionRepository;
    private final VerlaClarifyFormRepository clarifyFormRepository;
    private final VerlaEventInboxRepository eventInboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Builds a per-conversation status map for paged Dashboard history rows.
     */
    public Map<Long, String> resolveAll(List<VerlaConversation> conversations) {
        Map<Long, String> statuses = new LinkedHashMap<>();
        if (conversations == null) {
            return statuses;
        }
        for (VerlaConversation conversation : conversations) {
            if (conversation != null && conversation.getId() != null) {
                statuses.put(conversation.getId(), resolve(conversation));
            }
        }
        return statuses;
    }

    /**
     * Derives the Dashboard card status from task phase signals.
     */
    public String resolve(VerlaConversation conversation) {
        if (conversation == null) {
            return STATUS_PROGRESSING;
        }
        if (IntentLifecycle.conversationIsDraft(conversation.getIntentLifecycle())) {
            return STATUS_NEEDS_CHOICE;
        }

        String eventStatus = resolveFromLatestEvents(conversation);
        if (eventStatus != null) {
            return eventStatus;
        }

        VerlaTurn turn = findLatestTurn(conversation);
        if (turn == null) {
            return STATUS_PROGRESSING;
        }

        TurnStatus turnStatus = parseTurnStatus(turn.getStatus());
        if (turnStatus == TurnStatus.AWAITING_CLARIFY || hasOpenClarifyForm(conversation.getId())) {
            return STATUS_NEEDS_CHOICE;
        }
        if (turnStatus == TurnStatus.FAILED || turnStatus == TurnStatus.CANCELLED) {
            return STATUS_FAILED;
        }

        VerlaSession session = findRelevantSession(turn);
        SessionStatus sessionStatus = parseSessionStatus(session == null ? null : session.getStatus());
        if (sessionStatus == SessionStatus.FAILED || sessionStatus == SessionStatus.CANCELLED) {
            return STATUS_FAILED;
        }
        if (turnStatus == TurnStatus.COMPLETED) {
            return STATUS_COMPLETED;
        }
        if (sessionStatus == SessionStatus.CREATED
                || sessionStatus == SessionStatus.DISPATCHING
                || sessionStatus == SessionStatus.RUNNING
                || sessionStatus == SessionStatus.CANCELLING) {
            return STATUS_PROGRESSING;
        }
        if (turnStatus == TurnStatus.CREATED
                || turnStatus == TurnStatus.PLANNING
                || turnStatus == TurnStatus.DISPATCHING
                || turnStatus == TurnStatus.RUNNING_AGENT
                || turnStatus == TurnStatus.CANCELLING) {
            return STATUS_PROGRESSING;
        }
        return STATUS_PROGRESSING;
    }

    private String resolveFromLatestEvents(VerlaConversation conversation) {
        if (conversation.getId() == null) {
            return null;
        }
        List<VerlaEventInbox> events =
                eventInboxRepository.findRecentProcessedByConversation(conversation.getId(), 30);
        if (events == null || events.isEmpty()) {
            return null;
        }
        for (VerlaEventInbox event : events) {
            if (isFileChatEvent(event)) {
                continue;
            }
            String status = mapEventStatus(event);
            if (status != null) {
                return status;
            }
        }
        return null;
    }

    private String mapEventStatus(VerlaEventInbox event) {
        VerlaAgentEventType type = parseEventType(event == null ? null : event.getEventType());
        if (type == null) {
            return null;
        }
        return switch (type) {
            case PLAN_NEEDS_CLARIFY, AGENT_CLARIFY_FORM_ISSUED, ASSIGNMENT_CLARIFY_FORM_READY ->
                    STATUS_NEEDS_CHOICE;
            case ASSIGNMENT_INIT_COMPLETED, ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED ->
                    eventPayloadBoolean(event, "ready") || eventPayloadBoolean(event, "isReadyForGeneration")
                            ? STATUS_NEEDS_CHOICE
                            : STATUS_PROGRESSING;
            case ASSIGNMENT_AGENT_FLOW_COMPLETED, ASSIGNMENT_COMPLETED, AGENT_COMPLETED,
                    MATERIALS_COMPLETED -> STATUS_COMPLETED;
            case ASSIGNMENT_INIT_FAILED, ASSIGNMENT_DEEP_UNDERSTANDING_FAILED,
                    ASSIGNMENT_CLARIFY_FAILED, ASSIGNMENT_FAILED, ASSIGNMENT_AGENT_FLOW_FAILED,
                    AGENT_FAILED -> STATUS_FAILED;
            case ASSIGNMENT_CLARIFY_CANCELLED, ASSIGNMENT_CANCELLED,
                    ASSIGNMENT_AGENT_FLOW_CANCELLED, AGENT_CANCELLED -> STATUS_FAILED;
            case PLAN_INTENT_STARTED, PLAN_INTENT_STREAM_CHUNK,
                    ASSIGNMENT_INIT_STARTED, ASSIGNMENT_INIT_STREAM_CHUNK,
                    ASSIGNMENT_DEEP_UNDERSTANDING_STARTED, ASSIGNMENT_DEEP_UNDERSTANDING_STREAM_CHUNK,
                    ASSIGNMENT_CLARIFY_STARTED, ASSIGNMENT_CLARIFY_STREAM_CHUNK, ASSIGNMENT_CLARIFY_COMPLETED,
                    ASSIGNMENT_REQUIREMENT_UNDERSTANDING_STARTED,
                    ASSIGNMENT_REQUIREMENT_UNDERSTANDING_PROGRESS,
                    ASSIGNMENT_REQUIREMENT_UNDERSTANDING_COMPLETED,
                    ASSIGNMENT_STARTED, ASSIGNMENT_PLAN_DECOMPOSED, ASSIGNMENT_PROGRESS,
                    ASSIGNMENT_ARTIFACT_UPDATED, ASSIGNMENT_AGENT_FLOW_STARTED,
                    ASSIGNMENT_RUN_DISPATCH_QUEUED,
                    ASSIGNMENT_AGENT_NODE_UPDATED, ASSIGNMENT_WORKFLOW_NODE_UPDATED,
                    ASSIGNMENT_AGENT_NODE_DETAILED,
                    ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED, MATERIALS_STARTED,
                    AGENT_STARTED, AGENT_PLAN_DECOMPOSED, AGENT_STEP_STARTED,
                    AGENT_STEP_STREAM_CHUNK, AGENT_STEP_PROGRESS, AGENT_STEP_COMPLETED,
                    AGENT_BLOCK_ISSUED, AGENT_PROGRESS, AGENT_ARTIFACT_UPDATED,
                    AGENT_TOOL_CALL_RECORDED, ATTACHMENT_PARSED -> STATUS_PROGRESSING;
            case FILE_CHAT_STARTED, FILE_CHAT_STREAM_CHUNK, FILE_CHAT_COMPLETED,
                    FILE_CHAT_FAILED, FILE_CHAT_CANCELLED -> null;
            case PLAN_INTENT_RESOLVED, PLAN_TASK_NAME_RESOLVED -> null;
        };
    }

    private boolean eventPayloadBoolean(VerlaEventInbox event, String fieldName) {
        if (event == null || event.getPayloadJson() == null || event.getPayloadJson().isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(event.getPayloadJson());
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            JsonNode value = payload == null ? null : payload.get(fieldName);
            return value != null && value.isBoolean() && value.booleanValue();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasOpenClarifyForm(Long conversationId) {
        if (conversationId == null) {
            return false;
        }
        List<VerlaClarifyForm> forms = clarifyFormRepository.findOpenByConversation(conversationId);
        return forms != null && !forms.isEmpty();
    }

    private VerlaTurn findLatestTurn(VerlaConversation conversation) {
        if (conversation.getLastTurnId() != null) {
            VerlaTurn turn = turnRepository.findById(conversation.getLastTurnId());
            if (turn != null && !isFileChatTurn(turn)) {
                return turn;
            }
        }
        if (conversation.getId() == null) {
            return null;
        }
        List<VerlaTurn> turns = turnRepository.findRecentByConversation(conversation.getId(), 20);
        if (turns == null || turns.isEmpty()) {
            return null;
        }
        for (VerlaTurn turn : turns) {
            if (!isFileChatTurn(turn)) {
                return turn;
            }
        }
        return turns.get(0);
    }

    private VerlaSession findRelevantSession(VerlaTurn turn) {
        Long[] preferredSessionIds = {
                turn.getActiveSessionId(),
                turn.getAgentSessionId(),
                turn.getPlanSessionId()
        };
        for (Long sessionId : preferredSessionIds) {
            if (sessionId == null) {
                continue;
            }
            VerlaSession session = sessionRepository.findById(sessionId);
            if (session != null) {
                return session;
            }
        }
        if (turn.getId() == null) {
            return null;
        }
        List<VerlaSession> sessions = sessionRepository.findByTurn(turn.getId());
        return sessions == null || sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
    }

    private TurnStatus parseTurnStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TurnStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isFileChatEvent(VerlaEventInbox event) {
        VerlaAgentEventType type = parseEventType(event == null ? null : event.getEventType());
        return type == VerlaAgentEventType.FILE_CHAT_STARTED
                || type == VerlaAgentEventType.FILE_CHAT_STREAM_CHUNK
                || type == VerlaAgentEventType.FILE_CHAT_COMPLETED
                || type == VerlaAgentEventType.FILE_CHAT_FAILED
                || type == VerlaAgentEventType.FILE_CHAT_CANCELLED;
    }

    private boolean isFileChatTurn(VerlaTurn turn) {
        return turn != null && "FILE_CHAT".equals(turn.getResolvedIntent());
    }

    private SessionStatus parseSessionStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SessionStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private VerlaAgentEventType parseEventType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return VerlaAgentEventType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
