package com.studyagent.service.application.verla;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.common.verla.enums.VerlaSessionKind;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
import com.studyagent.common.verla.envelope.VerlaConversationRef;
import com.studyagent.common.verla.envelope.VerlaProducerInfo;
import com.studyagent.common.verla.envelope.VerlaSessionRef;
import com.studyagent.common.verla.envelope.VerlaTurnRef;
import com.studyagent.common.verla.util.VerlaCorrelationId;
import com.studyagent.service.application.MqOutboxService;
import com.studyagent.service.application.verla.dto.SendMessageCommand;
import com.studyagent.service.application.verla.dto.SendMessageResult;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import com.studyagent.service.domain.verla.state.SessionEvent;
import com.studyagent.service.domain.verla.state.SessionStateMachine;
import com.studyagent.service.domain.verla.state.SessionStatus;
import com.studyagent.service.domain.verla.state.TurnEvent;
import com.studyagent.service.domain.verla.state.TurnStateMachine;
import com.studyagent.service.domain.verla.state.TurnStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Verla 单轮交互编排器（PR-7 / PR-12 / PR-13）
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §7 / §9 / §11.2 / §11.3 / §11.5。
 * <ul>
 *     <li>{@link #onUserMessage(SendMessageCommand)} —— 用户消息入口（控制器调用）</li>
 *     <li>{@link #onPlanResolved(Long, String, Map, String)} —— Plan handler 调，PLAN_INTENT_RESOLVED</li>
 *     <li>{@link #onPlanNeedsClarify(Long, Map)} —— Plan handler 调，PLAN_NEEDS_CLARIFY</li>
 *     <li>{@link #onAgentStarted(Long)} / {@link #onAgentCompleted(Long, Map)} / {@link #onAgentFailed(Long, Map)} —— Agent handler 用</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerlaTurnOrchestrator {

    /** 与前端约定：伪装 user message 的 JSON，{@code kind} 常量 */
    private static final String ASSIGNMENT_CONFIRM_KIND = "verla_assignment_confirm";

    private static final String PRODUCER_SERVICE = "java-agent-service";
    private static final String INSTANCE_ID = resolveHostname();
    /**
     * 命令 exchange，与 RabbitMQConfig.COMMAND_EXCHANGE / VerlaRabbitConfig.COMMAND_EXCHANGE 保持一致。
     */
    private static final String DEFAULT_COMMAND_EXCHANGE = "studyagent.command";

    @Value("${verla.mq.command-exchange:" + DEFAULT_COMMAND_EXCHANGE + "}")
    private String commandExchange;

    private final VerlaConversationService conversationService;
    private final VerlaConversationRepository conversationRepository;
    private final VerlaTurnRepository turnRepository;
    private final VerlaSessionRepository sessionRepository;
    private final VerlaMessageRepository messageRepository;
    private final TurnStateMachine turnStateMachine;
    private final SessionStateMachine sessionStateMachine;
    private final MqOutboxService mqOutboxService;
    private final ObjectMapper objectMapper;

    // ==========================================================
    // 1) 用户消息入口
    // ==========================================================

    /**
     * 用户提交消息入口（核心）。
     * <p>
     * 若会话存在 {@link TurnStatus#AWAITING_ASSIGN_CONFIRM} 的 turn，则本条请求的 {@code text}
     * 必须为 JSON，且 {@code kind} 为 {@code verla_assignment_confirm}（表单填写或 skip），否则拒绝；
     * 成功则在<strong>同一 turn</strong>上插入 user 消息并派发 {@code cmd.agent.run}。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public SendMessageResult onUserMessage(SendMessageCommand cmd) {
        VerlaConversation conv = conversationService.loadWritable(cmd.getUserId(), cmd.getConversationId());

        Optional<VerlaTurn> awaitingAssign =
                turnRepository.findAwaitingAssignConfirmForUpdate(cmd.getConversationId());
        if (awaitingAssign.isPresent()) {
            Map<String, Object> confirm = parseAssignmentConfirmPayload(cmd.getText());
            if (confirm == null) {
                throw new BusinessException(ApiCode.BAD_REQUEST,
                        "会话正在等待作业确认：请将 text 设为 JSON，且 kind=\"" + ASSIGNMENT_CONFIRM_KIND + "\"");
            }
            return submitAssignmentConfirm(conv, awaitingAssign.get(), cmd, confirm);
        }

        VerlaMessage userMsg = insertUserMessagePlaceholder(conv.getId(), cmd);
        VerlaTurn turn = createTurn(conv.getId(), userMsg.getId());

        TurnStatus afterSubmit = turnStateMachine.next(TurnStatus.valueOf(turn.getStatus()), TurnEvent.SUBMIT);
        turn.setStatus(afterSubmit.name());
        turn.setUpdatedAt(LocalDateTime.now());
        turnRepository.save(turn);

        VerlaSession planSession = spawnPlanSession(conv, turn, cmd.getText());

        turn.setPlanSessionId(planSession.getId());
        turn.setActiveSessionId(planSession.getId());
        turn.setUpdatedAt(LocalDateTime.now());
        turnRepository.save(turn);

        userMsg.setTurnId(turn.getId());
        messageRepository.save(userMsg);

        conversationRepository.touchOnNewTurn(conv.getId(), turn.getId());

        log.info("[Verla] onUserMessage 完成: convId={}, turnId={}, planSessionId={}",
                conv.getId(), turn.getId(), planSession.getId());

        return SendMessageResult.builder()
                .turnId(turn.getId())
                .userMessageId(userMsg.getId())
                .planSessionId(planSession.getId())
                .build();
    }

    // ==========================================================
    // 2) Plan 阶段事件回调（PR-13）
    // ==========================================================

    /**
     * PLAN_INTENT_RESOLVED：plan session 完成 → turn 推进；作业意图时进入 {@link TurnStatus#AWAITING_ASSIGN_CONFIRM}
     * 等待前端用 JSON user 消息确认后再 {@code cmd.agent.run}；其它意图仍为 PLAN_OK→DISPATCHING 并立即派发 agent。
     * <p>
     * 文档 §9 / §11.5：
     * <pre>
     * plan session → SUCCEEDED；
     * conv.primaryIntent 沉淀；
     * turn: PLANNING --PLAN_OK--> DISPATCHING --START_AGENT--> RUNNING_AGENT；
     * 或 PLANNING --PLAN_OK_AWAIT_ASSIGN_CONFIRM--> AWAITING_ASSIGN_CONFIRM（作业意图）；
     * spawnAgentSession 写 outbox cmd.agent.run（仅 DISPATCHING 且尚无 agent_session_id 时）。
     * </pre>
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onPlanResolved(Long planSessionId, String intent, Map<String, Object> resolvedSlots,
                               String planAssistantContent) {
        VerlaSession planSession = sessionRepository.findByIdForUpdate(planSessionId);
        if (planSession == null) {
            log.warn("[Verla] onPlanResolved: plan session missing id={}", planSessionId);
            return;
        }
        VerlaTurn turn = turnRepository.findByIdForUpdate(planSession.getTurnId());
        VerlaConversation conv = conversationRepository.findById(turn.getConversationId());

        // 1) plan session 终态 SUCCEEDED（幂等：已经终态就不再变）
        SessionStatus curSess = SessionStatus.valueOf(planSession.getStatus());
        if (!curSess.isTerminal()) {
            SessionStatus nextSess = sessionStateMachine.next(curSess, SessionEvent.AGENT_COMPLETED);
            planSession.setStatus(nextSess.name());
            planSession.setEndedAt(LocalDateTime.now());
            planSession.setUpdatedAt(LocalDateTime.now());
            planSession.setResultJson(buildPlanResultJson(intent, resolvedSlots));
            sessionRepository.save(planSession);
        }

        // 2) turn：作业意图进入 AWAITING_ASSIGN_CONFIRM，否则 PLAN_OK → DISPATCHING
        TurnStatus curTurn = TurnStatus.valueOf(turn.getStatus());
        boolean planningJustFinished = (curTurn == TurnStatus.PLANNING);
        boolean deferAssignmentConfirm = intentNeedsAssignmentConfirm(intent);
        if (curTurn == TurnStatus.PLANNING) {
            TurnEvent planEv = deferAssignmentConfirm
                    ? TurnEvent.PLAN_OK_AWAIT_ASSIGN_CONFIRM
                    : TurnEvent.PLAN_OK;
            TurnStatus nextTurn = turnStateMachine.next(curTurn, planEv);
            turn.setStatus(nextTurn.name());
        } else if (curTurn.isTerminal()) {
            log.warn("[Verla] onPlanResolved: turn already terminal turnId={} status={}",
                    turn.getId(), curTurn);
            return;
        }
        turn.setResolvedIntent(intent);
        turn.setResolvedSlotsJson(serializeJson(resolvedSlots));
        turn.setUpdatedAt(LocalDateTime.now());
        turn.setLastProgressAt(LocalDateTime.now());
        turnRepository.save(turn);

        if (planningJustFinished && planAssistantContent != null && !planAssistantContent.isBlank()) {
            VerlaMessage assistant = VerlaMessage.builder()
                    .conversationId(turn.getConversationId())
                    .turnId(turn.getId())
                    .role("assistant")
                    .sourceSessionId(planSessionId)
                    .textContent(planAssistantContent)
                    .blocksJson(serializeJson(Map.of("phase", "plan_intent", "intent", intent)))
                    .createdAt(LocalDateTime.now())
                    .build();
            messageRepository.save(assistant);
        }

        // 3) 在 conversation 上沉淀 primaryIntent + bump version
        if (conv != null && intent != null && !intent.isBlank()
                && !intent.equals(conv.getPrimaryIntent())) {
            conv.setPrimaryIntent(intent);
            conv.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(conv);
        }
        if (conv != null) {
            conversationRepository.incrementVersion(conv.getId());
        }

        TurnStatus afterPlan = TurnStatus.valueOf(turn.getStatus());
        if (afterPlan == TurnStatus.DISPATCHING && turn.getAgentSessionId() == null) {
            spawnAgentSession(conv, turn, intent, resolvedSlots, null);
        } else if (deferAssignmentConfirm && afterPlan == TurnStatus.AWAITING_ASSIGN_CONFIRM) {
            log.info("[Verla] assignment intent awaits confirm JSON convId={} turnId={}",
                    turn.getConversationId(), turn.getId());
        }
    }

    /**
     * PLAN_NEEDS_CLARIFY：turn 进入 AWAITING_CLARIFY，等用户再次提交。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onPlanNeedsClarify(Long planSessionId, Map<String, Object> clarifyBlock) {
        VerlaSession planSession = sessionRepository.findByIdForUpdate(planSessionId);
        if (planSession == null) {
            log.warn("[Verla] onPlanNeedsClarify: plan session missing id={}", planSessionId);
            return;
        }
        VerlaTurn turn = turnRepository.findByIdForUpdate(planSession.getTurnId());

        // plan session 终态 SUCCEEDED（追问也算 plan 完成它的活）
        SessionStatus curSess = SessionStatus.valueOf(planSession.getStatus());
        if (!curSess.isTerminal()) {
            SessionStatus nextSess = sessionStateMachine.next(curSess, SessionEvent.AGENT_COMPLETED);
            planSession.setStatus(nextSess.name());
            planSession.setEndedAt(LocalDateTime.now());
            planSession.setUpdatedAt(LocalDateTime.now());
            planSession.setResultJson(serializeJson(Map.of("clarify", clarifyBlock)));
            sessionRepository.save(planSession);
        }

        TurnStatus curTurn = TurnStatus.valueOf(turn.getStatus());
        if (curTurn == TurnStatus.PLANNING) {
            TurnStatus nextTurn = turnStateMachine.next(curTurn, TurnEvent.PLAN_CLARIFY);
            turn.setStatus(nextTurn.name());
            turn.setUpdatedAt(LocalDateTime.now());
            turn.setLastProgressAt(LocalDateTime.now());
            turnRepository.save(turn);
        }

        // 写一条 assistant clarify 消息
        VerlaMessage assistant = VerlaMessage.builder()
                .conversationId(turn.getConversationId())
                .turnId(turn.getId())
                .role("assistant")
                .textContent(extractClarifyText(clarifyBlock))
                .blocksJson(serializeJson(clarifyBlock))
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.save(assistant);
    }

    // ==========================================================
    // 3) Agent 阶段事件回调（PR-14 起会更细，这里先打底）
    // ==========================================================

    /**
     * AGENT_STARTED：agent session DISPATCHING → RUNNING。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onAgentStarted(Long agentSessionId) {
        VerlaSession s = sessionRepository.findByIdForUpdate(agentSessionId);
        if (s == null) {
            return;
        }
        SessionStatus cur = SessionStatus.valueOf(s.getStatus());
        if (cur == SessionStatus.DISPATCHING) {
            SessionStatus next = sessionStateMachine.next(cur, SessionEvent.AGENT_STARTED);
            s.setStatus(next.name());
            s.setStartedAt(s.getStartedAt() == null ? LocalDateTime.now() : s.getStartedAt());
            s.setLastProgressAt(LocalDateTime.now());
            s.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(s);
        }
    }

    /**
     * AGENT_COMPLETED：agent session SUCCEEDED + turn COMPLETED。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onAgentCompleted(Long agentSessionId, Map<String, Object> result) {
        VerlaSession s = sessionRepository.findByIdForUpdate(agentSessionId);
        if (s == null) {
            return;
        }
        VerlaTurn turn = turnRepository.findByIdForUpdate(s.getTurnId());

        SessionStatus curSess = SessionStatus.valueOf(s.getStatus());
        if (!curSess.isTerminal()) {
            SessionStatus nextSess = sessionStateMachine.next(curSess, SessionEvent.AGENT_COMPLETED);
            s.setStatus(nextSess.name());
            s.setEndedAt(LocalDateTime.now());
            s.setUpdatedAt(LocalDateTime.now());
            s.setResultJson(serializeJson(result));
            sessionRepository.save(s);
        }

        TurnStatus curTurn = TurnStatus.valueOf(turn.getStatus());
        boolean agentTurnJustFinished = (curTurn == TurnStatus.RUNNING_AGENT);
        if (curTurn == TurnStatus.RUNNING_AGENT) {
            TurnStatus nextTurn = turnStateMachine.next(curTurn, TurnEvent.AGENT_OK);
            turn.setStatus(nextTurn.name());
            turn.setEndedAt(LocalDateTime.now());
            turn.setUpdatedAt(LocalDateTime.now());
            turn.setLastProgressAt(LocalDateTime.now());
            turnRepository.save(turn);
        }

        if (agentTurnJustFinished) {
            String reply = extractAssistantReply(result);
            if (reply != null && !reply.isBlank()) {
                VerlaMessage assistant = VerlaMessage.builder()
                        .conversationId(turn.getConversationId())
                        .turnId(turn.getId())
                        .role("assistant")
                        .sourceSessionId(agentSessionId)
                        .textContent(reply)
                        .blocksJson(serializeJson(result))
                        .createdAt(LocalDateTime.now())
                        .build();
                messageRepository.save(assistant);
            }
        }

        if (turn != null) {
            conversationRepository.incrementVersion(turn.getConversationId());
        }
    }

    /**
     * AGENT_FAILED：agent session FAILED + turn FAILED。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onAgentFailed(Long agentSessionId, Map<String, Object> errorBlock) {
        VerlaSession s = sessionRepository.findByIdForUpdate(agentSessionId);
        if (s == null) {
            return;
        }
        VerlaTurn turn = turnRepository.findByIdForUpdate(s.getTurnId());

        SessionStatus curSess = SessionStatus.valueOf(s.getStatus());
        if (!curSess.isTerminal()) {
            SessionStatus nextSess = sessionStateMachine.next(curSess, SessionEvent.AGENT_FAILED);
            s.setStatus(nextSess.name());
            s.setEndedAt(LocalDateTime.now());
            s.setUpdatedAt(LocalDateTime.now());
            s.setErrorJson(serializeJson(errorBlock));
            sessionRepository.save(s);
        }

        TurnStatus curTurn = TurnStatus.valueOf(turn.getStatus());
        if (!curTurn.isTerminal()) {
            TurnStatus nextTurn = turnStateMachine.next(curTurn, TurnEvent.AGENT_FAIL);
            turn.setStatus(nextTurn.name());
            turn.setEndedAt(LocalDateTime.now());
            turn.setUpdatedAt(LocalDateTime.now());
            turn.setErrorJson(serializeJson(errorBlock));
            turnRepository.save(turn);
        }
    }

    /**
     * AGENT_CANCELLED：agent session CANCELLED + turn CANCELLED。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onAgentCancelled(Long agentSessionId) {
        VerlaSession s = sessionRepository.findByIdForUpdate(agentSessionId);
        if (s == null) {
            return;
        }
        VerlaTurn turn = turnRepository.findByIdForUpdate(s.getTurnId());

        SessionStatus curSess = SessionStatus.valueOf(s.getStatus());
        if (!curSess.isTerminal()) {
            SessionStatus nextSess = sessionStateMachine.next(curSess, SessionEvent.AGENT_CANCELLED);
            s.setStatus(nextSess.name());
            s.setEndedAt(LocalDateTime.now());
            s.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(s);
        }

        TurnStatus curTurn = TurnStatus.valueOf(turn.getStatus());
        if (curTurn == TurnStatus.CANCELLING) {
            TurnStatus nextTurn = turnStateMachine.next(curTurn, TurnEvent.CANCEL_CONFIRMED);
            turn.setStatus(nextTurn.name());
            turn.setEndedAt(LocalDateTime.now());
            turn.setUpdatedAt(LocalDateTime.now());
            turnRepository.save(turn);
        }
    }

    // ==========================================================
    // 4) Session 派发
    // ==========================================================

    /**
     * 创建 plan session 并写 outbox 命令（同事务）
     */
    private VerlaSession spawnPlanSession(VerlaConversation conv, VerlaTurn turn, String userText) {
        LocalDateTime now = LocalDateTime.now();
        VerlaSession s = VerlaSession.builder()
                .conversationId(conv.getId())
                .turnId(turn.getId())
                .kind(VerlaSessionKind.PLAN.name())
                .status(SessionStatus.CREATED.name())
                .correlationId("placeholder")
                .expectedSeq(1L)
                .lastEventSeq(0L)
                .startedAt(now)
                .lastProgressAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        sessionRepository.save(s);

        s.setCorrelationId(VerlaCorrelationId.of(conv.getId(), turn.getId(), s.getId()));
        SessionStatus afterDispatch = sessionStateMachine.next(SessionStatus.CREATED, SessionEvent.DISPATCH);
        s.setStatus(afterDispatch.name());
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);

        VerlaCommandEnvelope env = buildPlanIntentEnvelope(conv, turn, s, userText);
        mqOutboxService.createVerlaCommand(env, commandExchange,
                VerlaCommandAction.CMD_PLAN_INTENT.getCode());
        return s;
    }

    /**
     * 创建 agent session 并写 outbox 命令（同事务）
     */
    private VerlaSession spawnAgentSession(VerlaConversation conv, VerlaTurn turn,
                                           String intent, Map<String, Object> resolvedSlots,
                                           Map<String, Object> assignmentConfirm) {
        LocalDateTime now = LocalDateTime.now();
        VerlaSession s = VerlaSession.builder()
                .conversationId(conv == null ? turn.getConversationId() : conv.getId())
                .turnId(turn.getId())
                .kind(VerlaSessionKind.AGENT.name())
                .featureCode(intent)
                .status(SessionStatus.CREATED.name())
                .correlationId("placeholder")
                .expectedSeq(1L)
                .lastEventSeq(0L)
                .startedAt(now)
                .lastProgressAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        sessionRepository.save(s);

        s.setCorrelationId(VerlaCorrelationId.of(s.getConversationId(), turn.getId(), s.getId()));
        SessionStatus afterDispatch = sessionStateMachine.next(SessionStatus.CREATED, SessionEvent.DISPATCH);
        s.setStatus(afterDispatch.name());
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);

        // turn: DISPATCHING → RUNNING_AGENT（START_AGENT 表示已发出 cmd.agent.run）
        TurnStatus curTurn = TurnStatus.valueOf(turn.getStatus());
        if (curTurn == TurnStatus.DISPATCHING) {
            TurnStatus next = turnStateMachine.next(curTurn, TurnEvent.START_AGENT);
            turn.setStatus(next.name());
        }
        turn.setActiveSessionId(s.getId());
        turn.setAgentSessionId(s.getId());
        turn.setUpdatedAt(LocalDateTime.now());
        turn.setLastProgressAt(LocalDateTime.now());
        turnRepository.save(turn);

        VerlaCommandEnvelope env = buildAgentRunEnvelope(conv, turn, s, intent, resolvedSlots, assignmentConfirm);
        mqOutboxService.createVerlaCommand(env, commandExchange,
                VerlaCommandAction.CMD_AGENT_RUN.getCode());
        return s;
    }

    // ==========================================================
    // helpers
    // ==========================================================

    private VerlaMessage insertUserMessagePlaceholder(Long conversationId, SendMessageCommand cmd) {
        VerlaMessage m = VerlaMessage.builder()
                .conversationId(conversationId)
                .turnId(0L)
                .role("user")
                .textContent(cmd.getText())
                .attachmentsJson(cmd.getAttachmentsJson())
                .createdAt(LocalDateTime.now())
                .build();
        return messageRepository.save(m);
    }

    private VerlaTurn createTurn(Long conversationId, Long userMessageId) {
        LocalDateTime now = LocalDateTime.now();
        VerlaTurn t = VerlaTurn.builder()
                .conversationId(conversationId)
                .userMessageId(userMessageId)
                .status(TurnStatus.CREATED.name())
                .completedSteps(0)
                .startedAt(now)
                .lastProgressAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return turnRepository.save(t);
    }

    private VerlaCommandEnvelope buildPlanIntentEnvelope(VerlaConversation conv, VerlaTurn turn,
                                                         VerlaSession session, String userText) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userText", userText);
        payload.put("primaryIntentHint", conv.getPrimaryIntent());

        return baseEnvelope(VerlaCommandAction.CMD_PLAN_INTENT, conv, turn, session)
                .payload(payload)
                .build();
    }

    private VerlaCommandEnvelope buildAgentRunEnvelope(VerlaConversation conv, VerlaTurn turn,
                                                       VerlaSession session, String intent,
                                                       Map<String, Object> slots,
                                                       Map<String, Object> assignmentConfirm) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("agentType", intent);
        payload.put("slots", slots == null ? Map.of() : slots);
        if (assignmentConfirm != null && !assignmentConfirm.isEmpty()) {
            payload.put("assignmentConfirm", assignmentConfirm);
        }
        payload.put("contextRef", Map.of(
                "type", "internal-rpc",
                "endpoint", "/v1/internal/verla/sessions/" + session.getId() + "/context",
                "convVersion", conv == null ? null : conv.getVersion()));

        return baseEnvelope(VerlaCommandAction.CMD_AGENT_RUN, conv, turn, session)
                .payload(payload)
                .build();
    }

    private VerlaCommandEnvelope.VerlaCommandEnvelopeBuilder baseEnvelope(
            VerlaCommandAction action, VerlaConversation conv, VerlaTurn turn, VerlaSession s) {
        return VerlaCommandEnvelope.builder()
                .schemaVersion(1)
                .messageId("cmd-" + UUID.randomUUID())
                .correlationId(s.getCorrelationId())
                .orderingKey(VerlaCorrelationId.orderingKey(s.getId()))
                .action(action.getCode())
                .timestamp(Instant.now())
                .producer(VerlaProducerInfo.builder()
                        .service(PRODUCER_SERVICE)
                        .instanceId(INSTANCE_ID)
                        .build())
                .conversation(VerlaConversationRef.builder()
                        .conversationId(s.getConversationId())
                        .userId(conv == null ? null : conv.getUserId())
                        .build())
                .turn(VerlaTurnRef.builder()
                        .turnId(turn.getId())
                        .build())
                .session(VerlaSessionRef.builder()
                        .sessionId(s.getId())
                        .kind(VerlaSessionKind.valueOf(s.getKind()))
                        .feature(s.getFeatureCode())
                        .build());
    }

    private SendMessageResult submitAssignmentConfirm(VerlaConversation conv, VerlaTurn turn,
                                                    SendMessageCommand cmd,
                                                    Map<String, Object> confirmPayload) {
        TurnStatus cur = TurnStatus.valueOf(turn.getStatus());
        if (cur != TurnStatus.AWAITING_ASSIGN_CONFIRM) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "turn is not awaiting assignment confirmation");
        }

        VerlaMessage userMsg = VerlaMessage.builder()
                .conversationId(conv.getId())
                .turnId(turn.getId())
                .role("user")
                .textContent(cmd.getText())
                .attachmentsJson(cmd.getAttachmentsJson())
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.save(userMsg);

        TurnStatus next = turnStateMachine.next(cur, TurnEvent.ASSIGNMENT_CONFIRM_RECEIVED);
        turn.setStatus(next.name());
        turn.setUpdatedAt(LocalDateTime.now());
        turn.setLastProgressAt(LocalDateTime.now());
        turnRepository.save(turn);

        VerlaConversation refreshedConv = conversationRepository.findById(conv.getId());
        Map<String, Object> slots = deserializeSlotsJson(turn.getResolvedSlotsJson());
        VerlaSession agentSession = spawnAgentSession(
                refreshedConv, turn, turn.getResolvedIntent(), slots, confirmPayload);

        if (refreshedConv != null) {
            conversationRepository.incrementVersion(refreshedConv.getId());
        }

        log.info("[Verla] assignment confirm → agent run convId={} turnId={} agentSessionId={}",
                conv.getId(), turn.getId(), agentSession.getId());

        return SendMessageResult.builder()
                .turnId(turn.getId())
                .userMessageId(userMsg.getId())
                .planSessionId(turn.getPlanSessionId())
                .agentSessionId(agentSession.getId())
                .build();
    }

    private Map<String, Object> parseAssignmentConfirmPayload(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> m = objectMapper.readValue(text.trim(), new TypeReference<Map<String, Object>>() {});
            Object kind = m.get("kind");
            if (!ASSIGNMENT_CONFIRM_KIND.equals(kind == null ? null : kind.toString())) {
                return null;
            }
            return m;
        } catch (Exception e) {
            log.debug("[Verla] not assignment confirm JSON: {}", e.getMessage());
            return null;
        }
    }

    private static boolean intentNeedsAssignmentConfirm(String intent) {
        if (intent == null) {
            return false;
        }
        String u = intent.trim().toUpperCase(Locale.ROOT);
        return "ASSIGNMENT".equals(u) || "ASSIGNEMNT".equals(u);
    }

    private Map<String, Object> deserializeSlotsJson(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> m = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return m != null ? new HashMap<>(m) : new HashMap<>();
        } catch (Exception e) {
            log.warn("[Verla] deserializeSlotsJson failed: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String buildPlanResultJson(String intent, Map<String, Object> slots) {
        Map<String, Object> map = new HashMap<>();
        map.put("intent", intent);
        map.put("slots", slots);
        return serializeJson(map);
    }

    private String serializeJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[Verla] json serialize failed: {}", e.getMessage());
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "json serialize failed");
        }
    }

    /** Py ASSIGNMENT_COMPLETED / AGENT_COMPLETED 等 payload 中可作为对用户可见回复的字段 */
    private static String extractAssistantReply(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return null;
        }
        String[] keys = {"finalResult", "summary", "answer", "text", "message", "content"};
        for (String key : keys) {
            Object v = result.get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static String extractClarifyText(Map<String, Object> clarifyBlock) {
        if (clarifyBlock == null) {
            return null;
        }
        Object q = clarifyBlock.get("question");
        return q == null ? null : q.toString();
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
