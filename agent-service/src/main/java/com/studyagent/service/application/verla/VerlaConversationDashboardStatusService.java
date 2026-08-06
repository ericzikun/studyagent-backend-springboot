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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    private static final int EVENT_SCAN_LIMIT = 30;
    private static final int TURN_SCAN_LIMIT = 20;

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
     * 批量预取 event/turn/session/clarify，避免 N+1（pageSize=20 时从 ~100 次 DB 降至 ~5 次）。
     */
    public Map<Long, String> resolveAll(List<VerlaConversation> conversations) {
        Map<Long, String> statuses = new LinkedHashMap<>();
        if (conversations == null || conversations.isEmpty()) {
            return statuses;
        }
        List<VerlaConversation> valid = conversations.stream()
                .filter(c -> c != null && c.getId() != null)
                .toList();
        if (valid.isEmpty()) {
            return statuses;
        }
        DashboardBatchContext batch = prefetch(valid);
        for (VerlaConversation conversation : valid) {
            statuses.put(conversation.getId(), resolve(conversation, batch));
        }
        return statuses;
    }

    /**
     * Derives the Dashboard card status from task phase signals.
     */
    public String resolve(VerlaConversation conversation) {
        if (conversation == null || conversation.getId() == null) {
            return STATUS_PROGRESSING;
        }
        return resolve(conversation, prefetch(List.of(conversation)));
    }

    private String resolve(VerlaConversation conversation, DashboardBatchContext batch) {
        if (conversation == null) {
            return STATUS_PROGRESSING;
        }
        if (IntentLifecycle.conversationIsDraft(conversation.getIntentLifecycle())) {
            return STATUS_NEEDS_CHOICE;
        }

        VerlaTurn turn = findLatestTurn(conversation, batch);
        String eventStatus = resolveFromLatestEvents(conversation.getId(), batch.eventsByConversation());
        if (eventStatus != null) {
            return reconcileEventStatusWithTurn(eventStatus, turn);
        }
        if (turn == null) {
            return STATUS_PROGRESSING;
        }

        TurnStatus turnStatus = parseTurnStatus(turn.getStatus());
        if (turnStatus == TurnStatus.AWAITING_CLARIFY || hasOpenClarifyForm(conversation.getId(), batch)) {
            return STATUS_NEEDS_CHOICE;
        }
        if (turnStatus == TurnStatus.FAILED || turnStatus == TurnStatus.CANCELLED) {
            return STATUS_FAILED;
        }

        VerlaSession session = findRelevantSession(turn, batch);
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

    private DashboardBatchContext prefetch(List<VerlaConversation> conversations) {
        List<Long> conversationIds = conversations.stream()
                .map(VerlaConversation::getId)
                .toList();

        Map<Long, List<VerlaEventInbox>> eventsByConversation =
                eventInboxRepository.findRecentProcessedByConversationIds(conversationIds, EVENT_SCAN_LIMIT);

        Set<Long> lastTurnIds = conversations.stream()
                .map(VerlaConversation::getLastTurnId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());

        Map<Long, List<VerlaTurn>> turnsByConversation =
                turnRepository.findRecentByConversationIds(conversationIds);

        Set<Long> sessionIds = new HashSet<>();
        for (VerlaConversation conversation : conversations) {
            if (conversation.getLastTurnId() != null) {
                VerlaTurn cached = findTurnById(conversation.getLastTurnId(), turnsByConversation, lastTurnIds);
                collectSessionIds(cached, sessionIds);
            }
        }
        for (List<VerlaTurn> turns : turnsByConversation.values()) {
            for (VerlaTurn turn : limitTurns(turns, TURN_SCAN_LIMIT)) {
                collectSessionIds(turn, sessionIds);
            }
        }

        Map<Long, VerlaTurn> turnsById = new HashMap<>();
        if (!lastTurnIds.isEmpty()) {
            for (VerlaTurn turn : turnRepository.findByIds(new ArrayList<>(lastTurnIds))) {
                if (turn != null && turn.getId() != null) {
                    turnsById.put(turn.getId(), turn);
                    collectSessionIds(turn, sessionIds);
                }
            }
        }

        Map<Long, VerlaSession> sessionsById = sessionRepository.findByIds(new ArrayList<>(sessionIds)).stream()
                .filter(s -> s != null && s.getId() != null)
                .collect(Collectors.toMap(VerlaSession::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        Set<Long> turnIdsForSessions = new HashSet<>();
        for (List<VerlaTurn> turns : turnsByConversation.values()) {
            for (VerlaTurn turn : limitTurns(turns, TURN_SCAN_LIMIT)) {
                if (turn.getId() != null) {
                    turnIdsForSessions.add(turn.getId());
                }
            }
        }
        turnIdsForSessions.addAll(lastTurnIds);

        Map<Long, List<VerlaSession>> sessionsByTurn = turnIdsForSessions.isEmpty()
                ? Map.of()
                : sessionRepository.findByTurnIds(new ArrayList<>(turnIdsForSessions));

        Map<Long, List<VerlaClarifyForm>> openFormsByConversation =
                clarifyFormRepository.findOpenByConversationIds(conversationIds);

        return new DashboardBatchContext(
                eventsByConversation,
                turnsByConversation,
                turnsById,
                sessionsById,
                sessionsByTurn,
                openFormsByConversation);
    }

    private void collectSessionIds(VerlaTurn turn, Set<Long> sessionIds) {
        if (turn == null) {
            return;
        }
        if (turn.getActiveSessionId() != null) {
            sessionIds.add(turn.getActiveSessionId());
        }
        if (turn.getAgentSessionId() != null) {
            sessionIds.add(turn.getAgentSessionId());
        }
        if (turn.getPlanSessionId() != null) {
            sessionIds.add(turn.getPlanSessionId());
        }
    }

    private VerlaTurn findTurnById(Long turnId, Map<Long, List<VerlaTurn>> turnsByConversation,
                                   Set<Long> lastTurnIds) {
        if (turnId == null) {
            return null;
        }
        for (List<VerlaTurn> turns : turnsByConversation.values()) {
            for (VerlaTurn turn : turns) {
                if (turnId.equals(turn.getId())) {
                    return turn;
                }
            }
        }
        return null;
    }

    private List<VerlaTurn> limitTurns(List<VerlaTurn> turns, int limit) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        return turns.size() <= limit ? turns : turns.subList(0, limit);
    }

    /**
     * AI Detection / Humanizer reruns can finish the latest turn while the newest
     * processed inbox row is still an intermediate AGENT_* artifact/progress event.
     * Prefer the completed turn over that stale progressing signal.
     */
    private String reconcileEventStatusWithTurn(String eventStatus, VerlaTurn turn) {
        if (!STATUS_PROGRESSING.equals(eventStatus) || turn == null) {
            return eventStatus;
        }
        TurnStatus turnStatus = parseTurnStatus(turn.getStatus());
        if (turnStatus == TurnStatus.COMPLETED) {
            return STATUS_COMPLETED;
        }
        return eventStatus;
    }

    private String resolveFromLatestEvents(Long conversationId, Map<Long, List<VerlaEventInbox>> eventsByConversation) {
        if (conversationId == null) {
            return null;
        }
        List<VerlaEventInbox> events = eventsByConversation.getOrDefault(conversationId, List.of());
        if (events.isEmpty()) {
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
                    MATERIALS_COMPLETED, AI_DETECTION_COMPLETED, AI_HUMANIZER_COMPLETED -> STATUS_COMPLETED;
            case ASSIGNMENT_INIT_FAILED, ASSIGNMENT_DEEP_UNDERSTANDING_FAILED,
                    ASSIGNMENT_CLARIFY_FAILED, ASSIGNMENT_FAILED, ASSIGNMENT_AGENT_FLOW_FAILED,
                    AGENT_FAILED, AI_DETECTION_FAILED, AI_HUMANIZER_FAILED -> STATUS_FAILED;
            case ASSIGNMENT_CLARIFY_CANCELLED, ASSIGNMENT_CANCELLED,
                    ASSIGNMENT_INIT_CANCELLED, ASSIGNMENT_DEEP_UNDERSTANDING_CANCELLED,
                    ASSIGNMENT_AGENT_FLOW_CANCELLED, AGENT_CANCELLED,
                    AI_DETECTION_CANCELLED, AI_HUMANIZER_CANCELLED -> STATUS_FAILED;
            case PLAN_INTENT_STARTED, PLAN_INTENT_STREAM_CHUNK,
                    ASSIGNMENT_INIT_STARTED, ASSIGNMENT_INIT_STREAM_CHUNK,
                    ASSIGNMENT_DEEP_UNDERSTANDING_STARTED, ASSIGNMENT_DEEP_UNDERSTANDING_STREAM_CHUNK,
                    ASSIGNMENT_CLARIFY_STARTED, ASSIGNMENT_CLARIFY_STREAM_CHUNK, ASSIGNMENT_CLARIFY_COMPLETED,
                    ASSIGNMENT_REQUIREMENT_UNDERSTANDING_STARTED,
                    ASSIGNMENT_REQUIREMENT_UNDERSTANDING_PROGRESS,
                    ASSIGNMENT_REQUIREMENT_UNDERSTANDING_COMPLETED,
                    ASSIGNMENT_STARTED, ASSIGNMENT_PLAN_DECOMPOSED, ASSIGNMENT_PROGRESS,
                    ASSIGNMENT_ARTIFACT_UPDATED, ASSIGNMENT_AGENT_FLOW_STARTED,
                    ASSIGNMENT_RUN_DISPATCH_QUEUED, ASSIGNMENT_RUN_DISPATCHED,
                    AI_DETECTION_RUN_DISPATCH_QUEUED, AI_HUMANIZER_RUN_DISPATCH_QUEUED,
                    AI_DETECTION_RUN_DISPATCHED, AI_HUMANIZER_RUN_DISPATCHED,
                    ASSIGNMENT_AGENT_NODE_UPDATED, ASSIGNMENT_WORKFLOW_NODE_UPDATED,
                    ASSIGNMENT_AGENT_NODE_DETAILED,
                    ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED, MATERIALS_STARTED,
                    AGENT_STARTED, AGENT_PLAN_DECOMPOSED, AGENT_STEP_STARTED,
                    AGENT_STEP_STREAM_CHUNK, AGENT_STEP_PROGRESS, AGENT_STEP_COMPLETED,
                    AGENT_BLOCK_ISSUED, AGENT_PROGRESS, AGENT_ARTIFACT_UPDATED,
                    AGENT_TOOL_CALL_RECORDED, ATTACHMENT_PARSED -> STATUS_PROGRESSING;
            case FILE_CHAT_STARTED, FILE_CHAT_STREAM_CHUNK, FILE_CHAT_COMPLETED,
                    FILE_CHAT_FAILED, FILE_CHAT_CANCELLED -> null;
            case ASSIGNMENT_CHAT_STARTED, ASSIGNMENT_CHAT_STREAM_CHUNK, ASSIGNMENT_CHAT_COMPLETED,
                    ASSIGNMENT_CHAT_FAILED, ASSIGNMENT_CHAT_CANCELLED -> null;
            case ARTIFACT_EDIT_PROPOSAL_STARTED, ARTIFACT_EDIT_PROPOSAL_READY,
                    ARTIFACT_EDIT_PROPOSAL_FAILED -> null;
            case PLAN_INTENT_RESOLVED, PLAN_TASK_NAME_RESOLVED, PLAN_TASK_NAME_FAILED -> null;
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

    private boolean hasOpenClarifyForm(Long conversationId, DashboardBatchContext batch) {
        if (conversationId == null) {
            return false;
        }
        List<VerlaClarifyForm> forms = batch.openFormsByConversation().getOrDefault(conversationId, List.of());
        return !forms.isEmpty();
    }

    private VerlaTurn findLatestTurn(VerlaConversation conversation, DashboardBatchContext batch) {
        if (conversation.getLastTurnId() != null) {
            VerlaTurn turn = batch.turnsById().get(conversation.getLastTurnId());
            if (turn == null) {
                turn = findTurnById(conversation.getLastTurnId(), batch.turnsByConversation(), Set.of());
            }
            if (turn != null && !isFileChatTurn(turn)) {
                return turn;
            }
        }
        if (conversation.getId() == null) {
            return null;
        }
        List<VerlaTurn> turns = limitTurns(
                batch.turnsByConversation().getOrDefault(conversation.getId(), List.of()),
                TURN_SCAN_LIMIT);
        if (turns.isEmpty()) {
            return null;
        }
        for (VerlaTurn turn : turns) {
            if (!isFileChatTurn(turn)) {
                return turn;
            }
        }
        return turns.get(0);
    }

    private VerlaSession findRelevantSession(VerlaTurn turn, DashboardBatchContext batch) {
        Long[] preferredSessionIds = {
                turn.getActiveSessionId(),
                turn.getAgentSessionId(),
                turn.getPlanSessionId()
        };
        for (Long sessionId : preferredSessionIds) {
            if (sessionId == null) {
                continue;
            }
            VerlaSession session = batch.sessionsById().get(sessionId);
            if (session != null) {
                return session;
            }
        }
        if (turn.getId() == null) {
            return null;
        }
        List<VerlaSession> sessions = batch.sessionsByTurn().getOrDefault(turn.getId(), List.of());
        return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
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

    private record DashboardBatchContext(
            Map<Long, List<VerlaEventInbox>> eventsByConversation,
            Map<Long, List<VerlaTurn>> turnsByConversation,
            Map<Long, VerlaTurn> turnsById,
            Map<Long, VerlaSession> sessionsById,
            Map<Long, List<VerlaSession>> sessionsByTurn,
            Map<Long, List<VerlaClarifyForm>> openFormsByConversation) {
    }
}
