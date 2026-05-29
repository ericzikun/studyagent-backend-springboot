package com.studyagent.service.application.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
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
import com.studyagent.service.application.verla.dto.PlanConfirmResult;
import com.studyagent.service.application.verla.dto.SendMessageCommand;
import com.studyagent.service.application.verla.dto.SendMessageResult;
import com.studyagent.service.application.verla.dto.FileChatAnalysisState;
import com.studyagent.service.application.verla.dto.FileChatAnalysisStatus;
import com.studyagent.service.application.verla.dto.FileChatMessageMeta;
import com.studyagent.service.application.verla.dto.FileChatPanelState;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
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
import com.studyagent.service.domain.verla.state.IntentLifecycle;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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

    private static final String PRODUCER_SERVICE = "java-agent-service";
    private static final String INSTANCE_ID = resolveHostname();
    private static final TypeReference<Map<String, Object>> MAP_STRING_OBJECT =
            new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST_OBJECT =
            new TypeReference<>() {};
    private static final int MAX_ASSISTANT_TEXT_CONTENT_CHARS = 32000;
    private static final String GENERATED_ARTIFACT_READY_TEXT =
            "Assignment output is ready. Open the generated artifact to view the full result.";
    private static final String PLAN_CONFIRM_YES_TEXT = "Yes, please help me finish it.";
    private static final String PLAN_CONFIRM_NO_TEXT = "No, let’s keep chatting.";
    private static final String ASSIGNMENT_START_YES_TEXT = "Yes, let's complete the assignment.";
    private static final String ASSIGNMENT_START_NO_TEXT = "No，let’s keep chatting.";
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
    private final VerlaAttachmentRepository attachmentRepository;
    private final TurnStateMachine turnStateMachine;
    private final SessionStateMachine sessionStateMachine;
    private final MqOutboxService mqOutboxService;
    private final ObjectMapper objectMapper;

    // ==========================================================
    // 1) 用户消息入口
    // ==========================================================

    /**
     * 用户提交消息入口（核心）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public SendMessageResult onUserMessage(SendMessageCommand cmd) {
        log.info("[Verla] onUserMessage received: convId={}, forceIntent='{}', text='{}'",
                cmd.getConversationId(), cmd.getForceIntent(),
                cmd.getText() == null ? null : cmd.getText().substring(0, Math.min(50, cmd.getText().length())));
        Optional<String> forced = parseForcedCapabilityIntent(cmd.getForceIntent());
        if (forced.isPresent()) {
            return onUserMessageForcedCapability(cmd, forced.get());
        }

        VerlaConversation conv = conversationService.loadWritable(cmd.getUserId(), cmd.getConversationId());

        VerlaMessage userMsg = insertUserMessagePlaceholder(conv.getId(), cmd);
        VerlaTurn turn = createTurn(conv.getId(), userMsg.getId());

        TurnStatus afterSubmit = turnStateMachine.next(TurnStatus.valueOf(turn.getStatus()), TurnEvent.SUBMIT);
        turn.setStatus(afterSubmit.name());
        turn.setUpdatedAt(LocalDateTime.now());
        turnRepository.save(turn);

        userMsg.setTurnId(turn.getId());
        messageRepository.save(userMsg);

        refreshConversationVersion(conv,
                conversationRepository.touchOnNewTurnAndGetVersion(conv.getId(), turn.getId()));

        VerlaSession planSession = spawnPlanSession(
                conv, turn, cmd.getText(), cmd.getAttachmentsJson(), cmd.isPlanConfirmRejected());

        turn.setPlanSessionId(planSession.getId());
        turn.setActiveSessionId(planSession.getId());
        turn.setUpdatedAt(LocalDateTime.now());
        turnRepository.save(turn);

        log.info("[Verla] onUserMessage 完成: convId={}, turnId={}, planSessionId={}",
                conv.getId(), turn.getId(), planSession.getId());

        return SendMessageResult.builder()
                .turnId(turn.getId())
                .userMessageId(userMsg.getId())
                .planSessionId(planSession.getId())
                .build();
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public SendMessageResult startFileChat(String userId, Long conversationId, String objectId, String message) {
        if (objectId == null || objectId.isBlank()) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "objectId required");
        }
        if (message == null || message.isBlank()) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "message required");
        }

        VerlaConversation conv = conversationService.loadWritable(userId, conversationId);
        VerlaAttachment attachment = attachmentRepository.findByObjectId(objectId);
        if (attachment == null || !conversationId.equals(attachment.getConversationId())) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "attachment");
        }

        VerlaMessage userMsg = insertFileChatUserMessagePlaceholder(conv.getId(), objectId, message);
        VerlaTurn turn = createTurn(conv.getId(), userMsg.getId());

        TurnStatus afterSkip = turnStateMachine.next(TurnStatus.valueOf(turn.getStatus()), TurnEvent.SKIP_PLAN);
        turn.setStatus(afterSkip.name());
        turn.setResolvedIntent("FILE_CHAT");
        turn.setResolvedSlotsJson(serializeJson(Map.of("objectId", objectId)));
        turn.setUpdatedAt(LocalDateTime.now());
        turnRepository.save(turn);

        userMsg.setTurnId(turn.getId());
        messageRepository.save(userMsg);

        refreshConversationVersion(conv,
                conversationRepository.touchOnNewTurnAndGetVersion(conv.getId(), turn.getId()));

        VerlaSession session = spawnFileChatSession(conv, turn, objectId, message);

        return SendMessageResult.builder()
                .turnId(turn.getId())
                .userMessageId(userMsg.getId())
                .agentSessionId(session.getId())
                .build();
    }

    /**
     * 跳过 Plan / 意图识别：前台已选定具体能力时直接派发 Py capability。
     */
    private SendMessageResult onUserMessageForcedCapability(SendMessageCommand cmd, String intent) {
        VerlaConversation conv = conversationService.loadWritable(cmd.getUserId(), cmd.getConversationId());
        // 在创建新 turn 之前判断是否首轮：lastTurnId == null 说明此前无 turn
        boolean isFirstTurn = conv.getLastTurnId() == null;

        VerlaMessage userMsg = insertUserMessagePlaceholder(conv.getId(), cmd);
        VerlaTurn turn = createTurn(conv.getId(), userMsg.getId());

        TurnStatus afterSkip = turnStateMachine.next(TurnStatus.valueOf(turn.getStatus()), TurnEvent.SKIP_PLAN);
        turn.setStatus(afterSkip.name());
        turn.setResolvedIntent(intent);
        turn.setResolvedSlotsJson(serializeJson(Map.of()));
        turn.setUpdatedAt(LocalDateTime.now());
        turnRepository.save(turn);

        userMsg.setTurnId(turn.getId());
        messageRepository.save(userMsg);

        if (intent != null && !intent.isBlank()) {
            if (!intent.equals(conv.getPrimaryIntent())) {
                conv.setPrimaryIntent(intent);
            }
            conv.setIntentLifecycle(IntentLifecycle.COMMITTED.getDbValue());
            conv.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(conv);
        }

        refreshConversationVersion(conv,
                conversationRepository.touchOnNewTurnAndGetVersion(conv.getId(), turn.getId()));
        refreshConversationVersion(conv,
                conversationRepository.incrementVersionAndGet(conv.getId()));

        // 第一轮对话生成会话标题（isFirstTurn 在创建 turn 之前通过 lastTurnId == null 判断，
        // 比依赖 turnCount 内存值更可靠）
        if (isFirstTurn) {
            spawnTaskNameSession(conv, turn, cmd.getText(), cmd.getAttachmentsJson());
        }

        VerlaSession agentSession;
        if (isAssignmentIntent(intent)) {
            agentSession = spawnAssignmentClarifyInitialSession(conv, turn, intent, Map.of());
        } else {
            VerlaCommandAction action = "AI_DETECTION".equals(intent)
                    ? VerlaCommandAction.CMD_DETECTION_RUN
                    : VerlaCommandAction.CMD_HUMANIZER_RUN;
            agentSession = spawnCapabilitySession(conv, turn, intent, Map.of(), action);
        }

        log.info("[Verla] forced capability intent={} convId={}, turnId={}, agentSessionId={}",
                intent, conv.getId(), turn.getId(), agentSession.getId());

        return SendMessageResult.builder()
                .turnId(turn.getId())
                .userMessageId(userMsg.getId())
                .planSessionId(null)
                .agentSessionId(agentSession.getId())
                .skipPlanReason("forced_capability")
                .build();
    }

    private Optional<String> parseForcedCapabilityIntent(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String n = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if ("AI_DETECTION".equals(n)) {
            return Optional.of("AI_DETECTION");
        }
        if ("AI_HUMANIZER".equals(n)) {
            return Optional.of("AI_HUMANIZER");
        }
        if (isAssignmentIntent(n)) {
            return Optional.of("ASSIGNMENT");
        }
        throw new BusinessException(ApiCode.PARAM_ERROR,
                "forceIntent must be ASSIGNMENT, AI_DETECTION or AI_HUMANIZER");
    }

    // ==========================================================
    // 2) Plan 阶段事件回调（PR-13）
    // ==========================================================

    /**
     * PLAN_INTENT_RESOLVED：plan session 完成 → turn 推进到 DISPATCHING → 派发 agent session。
     * <p>
     * 文档 §9 / §11.5：
     * <pre>
     * plan session → SUCCEEDED；
     * conv.primaryIntent 沉淀；
     * turn: PLANNING --PLAN_OK--> DISPATCHING；
     * · ASSIGNMENT：留在 DISPATCHING，等用户确认后再发 cmd.assignment.clarify stage_0；
     * · AI_DETECTION / AI_HUMANIZER / GENERAL_CHAT：只沉淀 intent，由 dashboard 询问用户确认后再跳转能力页；
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

        // 2) turn: PLANNING → DISPATCHING（PLAN_OK），如果已经在 DISPATCHING 之后则幂等忽略
        TurnStatus curTurn = TurnStatus.valueOf(turn.getStatus());
        boolean planningJustFinished = (curTurn == TurnStatus.PLANNING);
        if (curTurn == TurnStatus.PLANNING) {
            TurnStatus nextTurn = turnStateMachine.next(curTurn, TurnEvent.PLAN_OK);
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

        // 3) 在 conversation 上沉淀 primaryIntent + intentLifecycle（待 Dashboard 确认）+ bump version
        if (conv != null && intent != null && !intent.isBlank()) {
            if (!intent.equals(conv.getPrimaryIntent())) {
                conv.setPrimaryIntent(intent);
            }
            conv.setIntentLifecycle(IntentLifecycle.AWAITING_USER_CONFIRMATION.getDbValue());
            conv.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(conv);
        }
        if (conv != null) {
            conversationRepository.incrementVersion(conv.getId());
        }

        if (!isAssignmentIntent(intent)) {
            if (curTurn == TurnStatus.PLANNING) {
                TurnStatus nextTurn = turnStateMachine.next(curTurn, TurnEvent.PLAN_COMPLETE);
                turn.setStatus(nextTurn.name());
                turn.setEndedAt(LocalDateTime.now());
                turn.setUpdatedAt(LocalDateTime.now());
                turnRepository.save(turn);
            }
            log.info("[Verla] plan resolved as routed intent={}, turnId={} → wait for frontend confirmation",
                    intent, turn.getId());
            return;
        }

        if (planningJustFinished) {
            log.info("[Verla] plan resolved as assignment, turnId={} waits for user confirmation",
                    turn.getId());
        }
    }

    /**
     * 用户在前端确认进入作业功能后，先从已解析的 assignment plan 派发 clarify stage_0 session。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public SendMessageResult startAssignmentClarifyFromLatestPlan(String userId, Long conversationId) {
        VerlaConversation conv = conversationService.loadWritable(userId, conversationId);
        VerlaTurn latest = turnRepository.findRecentByConversation(conversationId, 1)
                .stream().findFirst().orElse(null);
        if (latest == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }

        VerlaTurn turn = turnRepository.findByIdForUpdate(latest.getId());
        String intent = turn.getResolvedIntent();
        if (!isAssignmentIntent(intent)) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE,
                    "latest plan is not assignment intent");
        }
        if (turn.getAgentSessionId() != null) {
            return SendMessageResult.builder()
                    .turnId(turn.getId())
                    .userMessageId(turn.getUserMessageId())
                    .planSessionId(turn.getPlanSessionId())
                    .agentSessionId(turn.getAgentSessionId())
                    .skipPlanReason("assignment_clarify_already_started")
                    .build();
        }

        TurnStatus curTurn = TurnStatus.valueOf(turn.getStatus());
        if (curTurn != TurnStatus.DISPATCHING) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE,
                    "assignment plan is not ready to start clarify");
        }

        // task_name session 已在 onUserMessage → spawnPlanSession 中创建（首轮时 turnCount 内存旧值==0），
        // 此处无需重复 dispatch，否则第二个 session 的 userText 依赖 DB 查询，可能拿到空值。

        VerlaSession agentSession = spawnAssignmentClarifyInitialSession(
                conv,
                turn,
                intent,
                parseSlotsJson(turn.getResolvedSlotsJson()));
        return SendMessageResult.builder()
                .turnId(turn.getId())
                .userMessageId(turn.getUserMessageId())
                .planSessionId(turn.getPlanSessionId())
                .agentSessionId(agentSession.getId())
                .build();
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public PlanConfirmResult confirmLatestPlan(String userId, Long conversationId,
                                               boolean confirmed,
                                               String somethingElseText) {
        VerlaConversation conv = conversationService.loadWritable(userId, conversationId);
        VerlaTurn latest = turnRepository.findRecentByConversation(conversationId, 1)
                .stream().findFirst().orElse(null);
        if (latest == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }

        VerlaTurn turn = turnRepository.findByIdForUpdate(latest.getId());
        String text = somethingElseText == null ? "" : somethingElseText.trim();

        if (!confirmed) {
            log.info("[Verla] start ");

            String rejectedText = text.isBlank() ? PLAN_CONFIRM_NO_TEXT : text;
            closePlanTurn(turn);
            SendMessageResult nextPlan = onUserMessage(SendMessageCommand.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .text(rejectedText)
                    .planConfirmRejected(true)
                    .skipPlanIfPossible(false)
                    .build());
            return PlanConfirmResult.builder()
                    .success(true)
                    .nextStage("planning")
                    .redirectUrl(null)
                    .messageResult(nextPlan)
                    .build();
        }

        recordPlanConfirmationMessage(turn, "confirmed", PLAN_CONFIRM_YES_TEXT);
        markIntentCommittedOnConversation(conv);

        String intent = turn.getResolvedIntent();
        if (isAssignmentIntent(intent)) {
            return PlanConfirmResult.builder()
                    .success(true)
                    .nextStage("understanding")
                    .redirectUrl("/dashboard/create?type=assignment&stage=understanding"
                            + "&surface=understanding&stream=verla&cid=" + conversationId)
                    .messageResult(SendMessageResult.builder()
                            .turnId(turn.getId())
                            .userMessageId(turn.getUserMessageId())
                            .planSessionId(turn.getPlanSessionId())
                            .agentSessionId(turn.getAgentSessionId())
                            .skipPlanReason("assignment_understanding_pending")
                            .build())
                    .build();
        }

        if (isAiDetectionIntent(intent)) {
            return PlanConfirmResult.builder()
                    .success(true)
                    .nextStage("redirecting")
                    .redirectUrl("/dashboard/create?type=ai-detection&stream=verla&cid=" + conversationId)
                    .messageResult(SendMessageResult.builder()
                            .turnId(turn.getId())
                            .userMessageId(turn.getUserMessageId())
                            .planSessionId(turn.getPlanSessionId())
                            .agentSessionId(turn.getAgentSessionId())
                            .build())
                    .build();
        }

        if (isAiHumanizerIntent(intent)) {
            return PlanConfirmResult.builder()
                    .success(true)
                    .nextStage("redirecting")
                    .redirectUrl("/dashboard/create?type=humanizer&stream=verla&cid=" + conversationId)
                    .messageResult(SendMessageResult.builder()
                            .turnId(turn.getId())
                            .userMessageId(turn.getUserMessageId())
                            .planSessionId(turn.getPlanSessionId())
                            .agentSessionId(turn.getAgentSessionId())
                            .build())
                    .build();
        }

        return PlanConfirmResult.builder()
                .success(true)
                .nextStage("dashboard")
                .redirectUrl("/dashboard")
                .messageResult(SendMessageResult.builder()
                        .turnId(turn.getId())
                        .userMessageId(turn.getUserMessageId())
                        .planSessionId(turn.getPlanSessionId())
                        .agentSessionId(turn.getAgentSessionId())
                        .build())
                .build();
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public SendMessageResult continueAssignmentClarify(String userId, Long conversationId,
                                                       Long previousSessionId,
                                                       String userChoice,
                                                       boolean userUnderstood,
                                                       String text,
                                                       List<String> objectIds) {
        VerlaConversation conv = conversationService.loadWritable(userId, conversationId);
        VerlaSession previous = previousSessionId == null ? null : sessionRepository.findById(previousSessionId);
        VerlaTurn baseTurn = previous == null
                ? turnRepository.findRecentByConversation(conversationId, 1).stream().findFirst().orElse(null)
                : turnRepository.findById(previous.getTurnId());
        if (baseTurn == null || !conversationId.equals(baseTurn.getConversationId())) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }

        VerlaTurn turn = turnRepository.findByIdForUpdate(baseTurn.getId());
        String normalizedChoice = normalizeClarifyChoice(userChoice);
        String messageText = buildContinueClarifyUserMessageText(normalizedChoice, text);
        VerlaMessage userMessage = VerlaMessage.builder()
                .conversationId(conversationId)
                .turnId(turn.getId())
                .role("user")
                .textContent(messageText)
                .attachmentsJson(objectIds == null || objectIds.isEmpty()
                        ? null : serializeJson(objectIds.stream()
                        .map(objectId -> Map.<String, Object>of("objectId", objectId))
                        .toList()))
                .blocksJson(serializeJson(Map.of(
                        "phase", "assignment_clarify",
                        "userChoice", normalizedChoice == null ? "" : normalizedChoice,
                        "userUnderstood", userUnderstood)))
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.save(userMessage);

        String resolvedIntent = isAssignmentIntent(turn.getResolvedIntent())
                ? turn.getResolvedIntent() : "ASSIGNMENT";
        Map<String, Object> resolvedSlots = parseSlotsJson(turn.getResolvedSlotsJson());

        // Both choices route to Phase 2 (deep_understanding), differing only by userUnderstood.
        refreshConversationVersion(conv,
                conversationRepository.incrementVersionAndGet(conversationId));
        VerlaSession nextSession = spawnAssignmentDeepUnderstandingSession(
                conv, turn, resolvedIntent, resolvedSlots, userUnderstood);

        return SendMessageResult.builder()
                .turnId(turn.getId())
                .userMessageId(userMessage.getId())
                .planSessionId(turn.getPlanSessionId())
                .agentSessionId(nextSession.getId())
                .build();
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public SendMessageResult finalizeAssignmentClarify(String userId, Long conversationId,
                                                       Long previousSessionId,
                                                       Map<String, Object> reservedFields,
                                                       List<Map<String, Object>> appendAskAnswers,
                                                       Map<String, Object> requirementForm,
                                                       List<String> objectIds) {
        VerlaConversation conv = conversationService.loadWritable(userId, conversationId);
        VerlaSession previous = previousSessionId == null ? null : sessionRepository.findById(previousSessionId);
        VerlaTurn baseTurn = previous == null
                ? turnRepository.findRecentByConversation(conversationId, 1).stream().findFirst().orElse(null)
                : turnRepository.findById(previous.getTurnId());
        if (baseTurn == null || !conversationId.equals(baseTurn.getConversationId())) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }

        List<Map<String, Object>> normalizedAnswers = normalizeAppendAskAnswers(appendAskAnswers);
        VerlaTurn turn = turnRepository.findByIdForUpdate(baseTurn.getId());
        String messageText = buildClarifyUserMessageText(
                "generation", "", reservedFields, normalizedAnswers, requirementForm);
        VerlaMessage userMessage = VerlaMessage.builder()
                .conversationId(conversationId)
                .turnId(turn.getId())
                .role("user")
                .textContent(messageText)
                .attachmentsJson(objectIds == null || objectIds.isEmpty()
                        ? null : serializeJson(objectIds.stream()
                        .map(objectId -> Map.<String, Object>of("objectId", objectId))
                        .toList()))
                .blocksJson(serializeJson(Map.of(
                        "phase", "assignment_clarify",
                        "userChoice", "generation",
                        "reservedFields", reservedFields == null ? Map.of() : reservedFields,
                        "appendAskAnswers", normalizedAnswers,
                        "requirementForm", requirementForm == null ? Map.of() : requirementForm)))
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.save(userMessage);

        String resolvedIntent = isAssignmentIntent(turn.getResolvedIntent())
                ? turn.getResolvedIntent() : "ASSIGNMENT";
        Map<String, Object> resolvedSlots = parseSlotsJson(turn.getResolvedSlotsJson());

        refreshConversationVersion(conv,
                conversationRepository.incrementVersionAndGet(conversationId));
        VerlaSession nextSession = spawnAssignmentClarifySession(
                conv, turn, resolvedIntent, resolvedSlots,
                "", objectIds, reservedFields, normalizedAnswers, requirementForm);

        return SendMessageResult.builder()
                .turnId(turn.getId())
                .userMessageId(userMessage.getId())
                .planSessionId(turn.getPlanSessionId())
                .agentSessionId(nextSession.getId())
                .build();
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public SendMessageResult startAssignmentRunFromFinalClarify(String userId, Long conversationId) {
        VerlaConversation conv = conversationService.loadWritable(userId, conversationId);
        VerlaTurn latest = turnRepository.findRecentByConversation(conversationId, 1)
                .stream().findFirst().orElse(null);
        if (latest == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }
        VerlaTurn turn = turnRepository.findByIdForUpdate(latest.getId());
        Map<String, Object> finalClarifyResult = findLatestFinalClarifyResult(turn);
        if (finalClarifyResult.isEmpty()) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE,
                    "assignment clarify is not finalized");
        }

        VerlaSession runSession = spawnAssignmentRunSession(
                conv,
                turn,
                isAssignmentIntent(turn.getResolvedIntent()) ? turn.getResolvedIntent() : "ASSIGNMENT",
                finalClarifyResult);
        return SendMessageResult.builder()
                .turnId(turn.getId())
                .userMessageId(turn.getUserMessageId())
                .planSessionId(turn.getPlanSessionId())
                .agentSessionId(runSession.getId())
                .build();
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

    @Transactional(propagation = Propagation.REQUIRED)
    public void onFileChatStarted(Long agentSessionId) {
        onAgentStarted(agentSessionId);
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
                        .blocksJson(serializeJson(sanitizeAssistantBlocks(result)))
                        .createdAt(LocalDateTime.now())
                        .build();
                messageRepository.save(assistant);
            }
        }

        if (turn != null) {
            conversationRepository.incrementVersion(turn.getConversationId());
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void onFileChatCompleted(Long agentSessionId, Map<String, Object> result) {
        VerlaSession s = sessionRepository.findByIdForUpdate(agentSessionId);
        if (s == null) {
            return;
        }
        VerlaTurn turn = turnRepository.findByIdForUpdate(s.getTurnId());
        Map<String, Object> safeResult = result == null ? Map.of() : result;
        String objectId = resolveFileChatObjectId(turn, safeResult);

        SessionStatus curSess = SessionStatus.valueOf(s.getStatus());
        if (!curSess.isTerminal()) {
            SessionStatus nextSess = sessionStateMachine.next(curSess, SessionEvent.AGENT_COMPLETED);
            s.setStatus(nextSess.name());
            s.setEndedAt(LocalDateTime.now());
            s.setUpdatedAt(LocalDateTime.now());
            s.setResultJson(serializeJson(safeResult));
            sessionRepository.save(s);
        }

        TurnStatus curTurn = TurnStatus.valueOf(turn.getStatus());
        boolean justCompleted = (curTurn == TurnStatus.RUNNING_AGENT);
        if (curTurn == TurnStatus.RUNNING_AGENT) {
            TurnStatus nextTurn = turnStateMachine.next(curTurn, TurnEvent.AGENT_OK);
            turn.setStatus(nextTurn.name());
            turn.setEndedAt(LocalDateTime.now());
            turn.setUpdatedAt(LocalDateTime.now());
            turn.setLastProgressAt(LocalDateTime.now());
            turnRepository.save(turn);
        }

        if (justCompleted) {
            String reply = extractFileChatAssistantReply(safeResult);
            if (reply != null && !reply.isBlank()) {
                VerlaMessage assistant = VerlaMessage.builder()
                        .conversationId(turn.getConversationId())
                        .turnId(turn.getId())
                        .role("assistant")
                        .sourceSessionId(agentSessionId)
                        .textContent(reply)
                        .blocksJson(serializeJson(withEventTypeWithoutStageForFrontend(
                                safeResult, "FILE_CHAT_COMPLETED")))
                        .metaJson(VerlaFileChatMetadataHelper.writeMessageMeta(
                                FileChatMessageMeta.builder()
                                        .scene(FileChatMessageMeta.SCENE_FILE_CHAT)
                                        .objectId(objectId)
                                        .build()))
                        .createdAt(LocalDateTime.now())
                        .build();
                messageRepository.save(assistant);
            }
        }

        conversationRepository.incrementVersion(turn.getConversationId());
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void onAssignmentDeepUnderstandingCompleted(Long agentSessionId, Map<String, Object> result) {
        Map<String, Object> normalizedResult = normalizeDeepUnderstandingResult(result);
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
            s.setResultJson(serializeJson(normalizedResult));
            sessionRepository.save(s);
        }

        String reply = extractAssistantReply(normalizedResult);
        if (reply != null && !reply.isBlank()) {
            VerlaMessage assistant = VerlaMessage.builder()
                    .conversationId(turn.getConversationId())
                    .turnId(turn.getId())
                    .role("assistant")
                    .sourceSessionId(agentSessionId)
                    .textContent(reply)
                    .blocksJson(serializeJson(withEventTypeWithoutStageForFrontend(
                            normalizedResult, "ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED")))
                    .createdAt(LocalDateTime.now())
                    .build();
            messageRepository.save(assistant);
        }

        conversationRepository.incrementVersion(turn.getConversationId());
    }

    /**
     * Persists the dedicated clarify-form-ready checkpoint.
     *
     * This event is intentionally separate from deep-understanding completion:
     * deep understanding may keep the user in chat, while this checkpoint is the
     * first moment the frontend should hydrate and render the follow-up form.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onAssignmentClarifyFormReady(Long agentSessionId, Map<String, Object> result) {
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

        String reply = extractAssistantReply(result);
        VerlaMessage assistant = VerlaMessage.builder()
                .conversationId(turn.getConversationId())
                .turnId(turn.getId())
                .role("assistant")
                .sourceSessionId(agentSessionId)
                .textContent(reply == null ? "" : reply)
                .blocksJson(serializeJson(withEventTypeWithoutStageForFrontend(
                        result, "ASSIGNMENT_CLARIFY_FORM_READY")))
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.save(assistant);

        conversationRepository.incrementVersion(turn.getConversationId());
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void onAssignmentClarifyCompleted(Long agentSessionId, Map<String, Object> result) {
        VerlaSession s = sessionRepository.findByIdForUpdate(agentSessionId);
        if (s == null) {
            return;
        }
        VerlaTurn turn = turnRepository.findByIdForUpdate(s.getTurnId());

        SessionStatus curSess = SessionStatus.valueOf(s.getStatus());
        boolean justCompleted = !curSess.isTerminal();
        if (justCompleted) {
            SessionStatus nextSess = sessionStateMachine.next(curSess, SessionEvent.AGENT_COMPLETED);
            s.setStatus(nextSess.name());
            s.setEndedAt(LocalDateTime.now());
            s.setUpdatedAt(LocalDateTime.now());
            s.setResultJson(serializeJson(result));
            sessionRepository.save(s);
        }

        String reply = extractAssistantReply(result);
        if (reply != null && !reply.isBlank()) {
            VerlaMessage assistant = VerlaMessage.builder()
                    .conversationId(turn.getConversationId())
                    .turnId(turn.getId())
                    .role("assistant")
                    .sourceSessionId(agentSessionId)
                    .textContent(reply)
                    .blocksJson(serializeJson(withEventTypeWithoutStage(
                            result, "ASSIGNMENT_CLARIFY_COMPLETED")))
                    .createdAt(LocalDateTime.now())
                    .build();
            messageRepository.save(assistant);
        }

        conversationRepository.incrementVersion(turn.getConversationId());

        // Auto-run: clarify signals ready → immediately spawn CMD_ASSIGNMENT_RUN in the same transaction.
        // justCompleted guard prevents duplicate spawning on event replay.
        if (justCompleted && Boolean.TRUE.equals(result.get("isReadyForGeneration"))) {
            VerlaConversation conv = conversationRepository.findById(turn.getConversationId());
            String intent = isAssignmentIntent(turn.getResolvedIntent())
                    ? turn.getResolvedIntent() : "ASSIGNMENT";
            log.info("[Verla] clarify isReadyForGeneration=true, auto-spawn run session turnId={} intent={}",
                    turn.getId(), intent);
            spawnAssignmentRunSession(conv, turn, intent, result);
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void onAssignmentInitCompleted(Long agentSessionId, Map<String, Object> result) {
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

        String reply = extractAssistantReply(result);
        if (reply != null && !reply.isBlank()) {
            String thinking = result.get("thinking") instanceof String t && !t.isBlank() ? t : null;
            VerlaMessage assistant = VerlaMessage.builder()
                    .conversationId(turn.getConversationId())
                    .turnId(turn.getId())
                    .role("assistant")
                    .sourceSessionId(agentSessionId)
                    .textContent(reply)
                    .blocksJson(serializeJson(withEventTypeWithoutStage(
                            result, "ASSIGNMENT_INIT_COMPLETED")))
                    .metaJson(thinking != null ? serializeJson(Map.of("thinking", thinking)) : null)
                    .createdAt(LocalDateTime.now())
                    .build();
            messageRepository.save(assistant);
        }

        conversationRepository.incrementVersion(turn.getConversationId());
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

    @Transactional(propagation = Propagation.REQUIRED)
    public void onFileChatFailed(Long agentSessionId, Map<String, Object> errorBlock) {
        onAgentFailed(agentSessionId, errorBlock);
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

    @Transactional(propagation = Propagation.REQUIRED)
    public void onFileChatCancelled(Long agentSessionId) {
        onAgentCancelled(agentSessionId);
    }

    // ==========================================================
    // 4) Session 派发
    // ==========================================================

    /**
     * 创建 plan session 并写 outbox 命令（同事务）
     */
    private VerlaSession spawnPlanSession(
            VerlaConversation conv, VerlaTurn turn, String userText, String attachmentsJson,
            boolean planConfirmRejected) {
        LocalDateTime now = LocalDateTime.now();

        // ── PLAN session：意图识别（主流程）────────────────────────────────────
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

        VerlaCommandEnvelope intentEnv = buildPlanIntentEnvelope(
                conv, turn, s, userText, planConfirmRejected);
        mqOutboxService.createVerlaCommand(intentEnv, commandExchange,
                VerlaCommandAction.CMD_PLAN_INTENT.getCode());

        // ── TASK_NAME session：仅第一轮对话触发，后续轮次跳过 ──────────────────
        // touchOnNewTurnAndGetVersion 已在 DB 将 turn_count +1，但内存中 conv.turnCount 仍是
        // 旧值（refreshConversationVersion 只刷 version 字段），故第一轮时旧值为 0。
        if (conv.getTurnCount() == null || conv.getTurnCount() == 0) {
            spawnTaskNameSession(conv, turn, userText, attachmentsJson);
        }

        return s;
    }

    /**
     * 为对话标题生成创建独立的轻量 session，并写入 MQ outbox。
     * <p>
     * 该 session 的生命周期与 PLAN session 完全独立：
     * Python 收到 cmd.plan.task_name 后直接调用 ConversationTitleService 并 emit PLAN_TASK_NAME_RESOLVED，
     * Java VerlaConversationTitleEventHandler 接收后更新 verla_conversation.title。
     */
    private void spawnTaskNameSession(VerlaConversation conv, VerlaTurn turn, String userText,
                                      String attachmentsJson) {
        LocalDateTime now = LocalDateTime.now();
        VerlaSession ts = VerlaSession.builder()
                .conversationId(conv.getId())
                .turnId(turn.getId())
                .kind(VerlaSessionKind.TASK_NAME.name())
                .status(SessionStatus.CREATED.name())
                .correlationId("placeholder")
                .expectedSeq(1L)
                .lastEventSeq(0L)
                .startedAt(now)
                .lastProgressAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        sessionRepository.save(ts);

        ts.setCorrelationId(VerlaCorrelationId.of(conv.getId(), turn.getId(), ts.getId()));
        SessionStatus afterDispatch = sessionStateMachine.next(SessionStatus.CREATED, SessionEvent.DISPATCH);
        ts.setStatus(afterDispatch.name());
        ts.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(ts);

        VerlaCommandEnvelope taskNameEnv = buildPlanTaskNameEnvelope(
                conv, turn, ts, userText, attachmentsJson);
        mqOutboxService.createVerlaCommand(taskNameEnv, commandExchange,
                VerlaCommandAction.CMD_PLAN_TASK_NAME.getCode());
    }

    /**
     * 创建 assignment clarify 首轮 session 并写 outbox 命令（同事务）
     */
    private VerlaSession spawnAssignmentClarifyInitialSession(VerlaConversation conv, VerlaTurn turn,
                                                              String intent,
                                                              Map<String, Object> resolvedSlots) {
        LocalDateTime now = LocalDateTime.now();
        VerlaSession s = VerlaSession.builder()
                .conversationId(conv == null ? turn.getConversationId() : conv.getId())
                .turnId(turn.getId())
                .kind(VerlaSessionKind.ASSIGNMENT.name())
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

        // turn: DISPATCHING → RUNNING_AGENT（START_AGENT 表示已发出 assignment clarify stage_0）
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

        VerlaCommandEnvelope env = buildAssignmentInitEnvelope(
                conv, turn, s, intent, resolvedSlots);
        mqOutboxService.createVerlaCommand(env, commandExchange,
                VerlaCommandAction.CMD_ASSIGNMENT_INIT.getCode());
        return s;
    }

    private VerlaSession spawnAssignmentDeepUnderstandingSession(VerlaConversation conv, VerlaTurn turn,
                                                                  String intent,
                                                                  Map<String, Object> resolvedSlots,
                                                                  boolean userUnderstood) {
        LocalDateTime now = LocalDateTime.now();
        VerlaSession s = VerlaSession.builder()
                .conversationId(conv == null ? turn.getConversationId() : conv.getId())
                .turnId(turn.getId())
                .kind(VerlaSessionKind.ASSIGNMENT.name())
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
        s.setStatus(sessionStateMachine.next(SessionStatus.CREATED, SessionEvent.DISPATCH).name());
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);

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

        VerlaCommandEnvelope env = buildAssignmentDeepUnderstandingEnvelope(
                conv, turn, s, intent, resolvedSlots, userUnderstood);
        mqOutboxService.createVerlaCommand(env, commandExchange,
                VerlaCommandAction.CMD_ASSIGNMENT_DEEP_UNDERSTANDING.getCode());
        return s;
    }

    private VerlaSession spawnAssignmentClarifySession(VerlaConversation conv, VerlaTurn turn,
                                                       String intent, Map<String, Object> resolvedSlots,
                                                       String userText,
                                                       List<String> objectIds,
                                                       Map<String, Object> reservedFields,
                                                       List<Map<String, Object>> appendAskAnswers,
                                                       Map<String, Object> requirementForm) {
        LocalDateTime now = LocalDateTime.now();
        VerlaSession s = VerlaSession.builder()
                .conversationId(conv == null ? turn.getConversationId() : conv.getId())
                .turnId(turn.getId())
                .kind(VerlaSessionKind.ASSIGNMENT.name())
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
        s.setStatus(sessionStateMachine.next(SessionStatus.CREATED, SessionEvent.DISPATCH).name());
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);

        turn.setActiveSessionId(s.getId());
        turn.setAgentSessionId(s.getId());
        turn.setUpdatedAt(LocalDateTime.now());
        turn.setLastProgressAt(LocalDateTime.now());
        turnRepository.save(turn);

        VerlaCommandEnvelope env = buildAssignmentClarifyEnvelope(
                conv, turn, s, intent, resolvedSlots, userText,
                objectIds, reservedFields, appendAskAnswers, requirementForm);
        mqOutboxService.createVerlaCommand(env, commandExchange,
                VerlaCommandAction.CMD_ASSIGNMENT_CLARIFY.getCode());
        return s;
    }

    private VerlaSession spawnAssignmentRunSession(VerlaConversation conv, VerlaTurn turn,
                                                   String intent,
                                                   Map<String, Object> finalClarifyResult) {
        LocalDateTime now = LocalDateTime.now();
        VerlaSession s = VerlaSession.builder()
                .conversationId(conv == null ? turn.getConversationId() : conv.getId())
                .turnId(turn.getId())
                .kind(VerlaSessionKind.ASSIGNMENT.name())
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
        s.setStatus(sessionStateMachine.next(SessionStatus.CREATED, SessionEvent.DISPATCH).name());
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);

        turn.setActiveSessionId(s.getId());
        turn.setAgentSessionId(s.getId());
        turn.setUpdatedAt(LocalDateTime.now());
        turn.setLastProgressAt(LocalDateTime.now());
        turnRepository.save(turn);

        VerlaCommandEnvelope env = buildAssignmentRunEnvelope(conv, turn, s, intent, finalClarifyResult);
        mqOutboxService.createVerlaCommand(env, commandExchange,
                VerlaCommandAction.CMD_ASSIGNMENT_RUN.getCode());
        return s;
    }

    /**
     * AI 检测 / Humanizer：创建 agent session 并写 outbox（同事务），立即发往 Py。
     */
    private VerlaSession spawnCapabilitySession(VerlaConversation conv, VerlaTurn turn,
                                               String intent, Map<String, Object> resolvedSlots,
                                               VerlaCommandAction commandAction) {
        if (turn.getAgentSessionId() != null) {
            log.info("[Verla] capability already dispatched turnId={} agentSessionId={}, skip duplicate",
                    turn.getId(), turn.getAgentSessionId());
            return sessionRepository.findById(turn.getAgentSessionId());
        }
        LocalDateTime now = LocalDateTime.now();
        VerlaSession s = VerlaSession.builder()
                .conversationId(conv == null ? turn.getConversationId() : conv.getId())
                .turnId(turn.getId())
                .kind(VerlaSessionKind.ASSIGNMENT.name())
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

        TurnStatus curTurn = TurnStatus.valueOf(turn.getStatus());
        if (curTurn == TurnStatus.DISPATCHING) {
            TurnStatus next = turnStateMachine.next(curTurn, TurnEvent.START_AGENT);
            turn.setStatus(next.name());
        } else {
            log.warn("[Verla] spawnCapabilitySession: turn not DISPATCHING, status={} turnId={}",
                    curTurn, turn.getId());
        }
        turn.setActiveSessionId(s.getId());
        turn.setAgentSessionId(s.getId());
        turn.setUpdatedAt(LocalDateTime.now());
        turn.setLastProgressAt(LocalDateTime.now());
        turnRepository.save(turn);

        VerlaCommandEnvelope env = buildCapabilityEnvelope(
                conv, turn, s, intent, resolvedSlots, commandAction,
                resolveTurnUserText(turn));
        mqOutboxService.createVerlaCommand(env, commandExchange, commandAction.getCode());
        log.info("[Verla] dispatched {} turnId={} sessionId={}", commandAction.getCode(), turn.getId(), s.getId());
        return s;
    }

    private VerlaSession spawnFileChatSession(VerlaConversation conv, VerlaTurn turn,
                                              String objectId, String message) {
        LocalDateTime now = LocalDateTime.now();
        VerlaSession s = VerlaSession.builder()
                .conversationId(conv.getId())
                .turnId(turn.getId())
                .kind(VerlaSessionKind.FILE_CHAT.name())
                .featureCode("FILE_CHAT")
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
        s.setStatus(sessionStateMachine.next(SessionStatus.CREATED, SessionEvent.DISPATCH).name());
        s.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(s);

        turn.setStatus(TurnStatus.RUNNING_AGENT.name());
        turn.setActiveSessionId(s.getId());
        turn.setAgentSessionId(s.getId());
        turn.setUpdatedAt(LocalDateTime.now());
        turn.setLastProgressAt(LocalDateTime.now());
        turnRepository.save(turn);

        VerlaCommandEnvelope env = buildFileChatEnvelope(conv, turn, s, objectId, message);
        mqOutboxService.createVerlaCommand(env, commandExchange, VerlaCommandAction.CMD_FILE_CHAT.getCode());
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

    private VerlaMessage insertFileChatUserMessagePlaceholder(Long conversationId, String objectId, String text) {
        VerlaMessage m = VerlaMessage.builder()
                .conversationId(conversationId)
                .turnId(0L)
                .role("user")
                .textContent(text)
                .metaJson(VerlaFileChatMetadataHelper.writeMessageMeta(
                        com.studyagent.service.application.verla.dto.FileChatMessageMeta.builder()
                                .scene(com.studyagent.service.application.verla.dto.FileChatMessageMeta.SCENE_FILE_CHAT)
                                .objectId(objectId)
                                .build()))
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

    private void recordPlanConfirmationMessage(VerlaTurn turn, String choice, String text) {
        Map<String, Object> block = new HashMap<>();
        block.put("phase", "plan_confirmation");
        block.put("choice", choice);
        if (text != null && !text.isBlank()) {
            block.put("somethingElseText", text);
        }
        VerlaMessage message = VerlaMessage.builder()
                .conversationId(turn.getConversationId())
                .turnId(turn.getId())
                .role("user")
                .textContent(text == null || text.isBlank() ? choice : text)
                .blocksJson(serializeJson(block))
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.save(message);
    }

    private void closePlanTurn(VerlaTurn turn) {
        TurnStatus curTurn = TurnStatus.valueOf(turn.getStatus());
        if (curTurn.isTerminal()) {
            return;
        }
        if (curTurn == TurnStatus.DISPATCHING || curTurn == TurnStatus.PLANNING) {
            TurnStatus nextTurn = turnStateMachine.next(curTurn, TurnEvent.PLAN_COMPLETE);
            turn.setStatus(nextTurn.name());
            turn.setEndedAt(LocalDateTime.now());
            turn.setUpdatedAt(LocalDateTime.now());
            turn.setLastProgressAt(LocalDateTime.now());
            turnRepository.save(turn);
        }
    }

    private void clearPrimaryIntent(VerlaConversation conv) {
        if (conv == null || conv.getPrimaryIntent() == null) {
            return;
        }
        conv.setPrimaryIntent(null);
        conv.setIntentLifecycle(IntentLifecycle.NONE.getDbValue());
        conv.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conv);
        conversationRepository.incrementVersion(conv.getId());
    }

    private void markIntentCommittedOnConversation(VerlaConversation conv) {
        if (conv == null) {
            return;
        }
        conv.setIntentLifecycle(IntentLifecycle.COMMITTED.getDbValue());
        conv.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conv);
        conversationRepository.incrementVersion(conv.getId());
    }

    private VerlaCommandEnvelope buildPlanIntentEnvelope(VerlaConversation conv, VerlaTurn turn,
                                                         VerlaSession session, String userText,
                                                         boolean planConfirmRejected) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userText", userText);
        payload.put("primaryIntentHint", conv.getPrimaryIntent());
        if (planConfirmRejected) {
            payload.put("planConfirmRejected", true);
        }
        payload.put("contextRef", buildContextRef(
                "/v1/internal/verla/conversations/" + conv.getId() + "/context",
                conv.getVersion()));

        return baseEnvelope(VerlaCommandAction.CMD_PLAN_INTENT, conv, turn, session)
                .payload(payload)
                .build();
    }

    private VerlaCommandEnvelope buildPlanTaskNameEnvelope(VerlaConversation conv, VerlaTurn turn,
                                                           VerlaSession session, String userText,
                                                           String attachmentsJson) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userText", userText != null ? userText : "");
        List<Map<String, Object>> attachments = parseUploadedAttachments(attachmentsJson);
        if (!attachments.isEmpty()) {
            payload.put("attachments", attachments);
            payload.put("objectIds", attachments.stream()
                    .map(attachment -> attachment.get("objectId"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .distinct()
                    .toList());
        }
        return baseEnvelope(VerlaCommandAction.CMD_PLAN_TASK_NAME, conv, turn, session)
                .payload(payload)
                .build();
    }

    private VerlaCommandEnvelope buildAssignmentInitEnvelope(VerlaConversation conv, VerlaTurn turn,
                                                             VerlaSession session, String intent,
                                                             Map<String, Object> slots) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("agentType", intent);
        payload.put("objectIds", List.of());
        payload.put("slots", slots == null ? Map.of() : slots);
        payload.put("userText", resolveTurnUserText(turn));
        payload.put("contextRef", buildContextRef(
                "/v1/internal/verla/sessions/" + session.getId() + "/context",
                conv == null ? null : conv.getVersion()));

        return baseEnvelope(VerlaCommandAction.CMD_ASSIGNMENT_INIT, conv, turn, session)
                .payload(payload)
                .build();
    }

    private VerlaCommandEnvelope buildAssignmentDeepUnderstandingEnvelope(
            VerlaConversation conv, VerlaTurn turn, VerlaSession session,
            String intent, Map<String, Object> slots, boolean userUnderstood) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("agentType", intent);
        payload.put("objectIds", List.of());
        payload.put("slots", slots == null ? Map.of() : slots);
        payload.put("userText", resolveTurnUserText(turn));
        payload.put("userUnderstood", userUnderstood);
        payload.put("contextRef", buildContextRef(
                "/v1/internal/verla/sessions/" + session.getId() + "/context",
                conv == null ? null : conv.getVersion()));

        return baseEnvelope(VerlaCommandAction.CMD_ASSIGNMENT_DEEP_UNDERSTANDING, conv, turn, session)
                .payload(payload)
                .build();
    }

    private VerlaCommandEnvelope buildAssignmentClarifyEnvelope(VerlaConversation conv, VerlaTurn turn,
                                                               VerlaSession session, String intent,
                                                               Map<String, Object> slots,
                                                               String userText,
                                                               List<String> objectIds,
                                                               Map<String, Object> reservedFields,
                                                               List<Map<String, Object>> appendAskAnswers,
                                                               Map<String, Object> requirementForm) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("agentType", intent);
        payload.put("objectIds", objectIds == null ? List.of() : objectIds);
        payload.put("slots", slots == null ? Map.of() : slots);
        if (userText != null && !userText.isBlank()) {
            payload.put("userText", userText);
        } else {
            payload.put("userText", "");
        }
        payload.put("reservedFields", reservedFields == null ? Map.of() : reservedFields);
        payload.put("appendAskAnswers", appendAskAnswers == null ? List.of() : appendAskAnswers);
        if (requirementForm != null) {
            payload.put("requirementForm", requirementForm);
        }
        payload.put("contextRef", buildContextRef(
                "/v1/internal/verla/sessions/" + session.getId() + "/context",
                conv == null ? null : conv.getVersion()));

        return baseEnvelope(VerlaCommandAction.CMD_ASSIGNMENT_CLARIFY, conv, turn, session)
                .payload(payload)
                .build();
    }

    private VerlaCommandEnvelope buildCapabilityEnvelope(VerlaConversation conv, VerlaTurn turn,
                                                        VerlaSession session, String intent,
                                                        Map<String, Object> slots,
                                                        VerlaCommandAction action,
                                                        String userMessageText) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("agentType", intent);
        payload.put("slots", slots == null ? Map.of() : slots);
        if (userMessageText != null && !userMessageText.isBlank()) {
            payload.put("userText", userMessageText.trim());
        }
        payload.put("contextRef", buildContextRef(
                "/v1/internal/verla/sessions/" + session.getId() + "/context",
                conv == null ? null : conv.getVersion()));

        return baseEnvelope(action, conv, turn, session)
                .payload(payload)
                .build();
    }

    private VerlaCommandEnvelope buildFileChatEnvelope(VerlaConversation conv, VerlaTurn turn,
                                                       VerlaSession session, String objectId, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("objectId", objectId);
        payload.put("message", message);
        payload.put("contextRef", buildContextRef(
                "/v1/internal/verla/sessions/" + session.getId() + "/context",
                conv == null ? null : conv.getVersion()));

        return baseEnvelope(VerlaCommandAction.CMD_FILE_CHAT, conv, turn, session)
                .payload(payload)
                .build();
    }

    private VerlaCommandEnvelope buildAssignmentRunEnvelope(VerlaConversation conv, VerlaTurn turn,
                                                           VerlaSession session, String intent,
                                                           Map<String, Object> finalClarifyResult) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("agentType", intent);
        payload.put("requirementForm", finalClarifyResult.getOrDefault("requirementForm", Map.of()));
        payload.put("reservedFields", finalClarifyResult.getOrDefault("reservedFields", Map.of()));
        payload.put("appendAskAnswers", finalClarifyResult.getOrDefault("appendAskAnswers", List.of()));
        payload.put("requirementUnderstanding",
                finalClarifyResult.getOrDefault("requirementUnderstanding", ""));
        payload.put("contextRef", buildContextRef(
                "/v1/internal/verla/sessions/" + session.getId() + "/context",
                conv == null ? null : conv.getVersion()));

        return baseEnvelope(VerlaCommandAction.CMD_ASSIGNMENT_RUN, conv, turn, session)
                .payload(payload)
                .build();
    }

    /** 本轮用户输入正文（Humanizer/检测等在 Py 侧优先读 payload.userText，避免强依赖 internal context）。 */
    private String resolveTurnUserText(VerlaTurn turn) {
        if (turn.getUserMessageId() == null) {
            return "";
        }
        VerlaMessage m = messageRepository.findById(turn.getUserMessageId());
        if (m == null || m.getTextContent() == null) {
            return "";
        }
        return m.getTextContent().trim();
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

    private String buildPlanResultJson(String intent, Map<String, Object> slots) {
        Map<String, Object> map = new HashMap<>();
        map.put("intent", intent);
        map.put("slots", slots);
        return serializeJson(map);
    }

    private Map<String, Object> parseSlotsJson(String slotsJson) {
        if (slotsJson == null || slotsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(slotsJson, MAP_STRING_OBJECT);
        } catch (Exception e) {
            log.warn("[Verla] resolved slots json parse failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private List<Map<String, Object>> parseUploadedAttachments(String attachmentsJson) {
        if (attachmentsJson == null || attachmentsJson.isBlank()) {
            return List.of();
        }
        try {
            List<Object> rawAttachments = objectMapper.readValue(attachmentsJson, LIST_OBJECT);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object raw : rawAttachments) {
                Map<String, Object> attachment = normalizeUploadedAttachment(raw);
                if (!attachment.isEmpty()) {
                    result.add(attachment);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[Verla] attachments json parse failed for task name command: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> normalizeUploadedAttachment(Object raw) {
        if (raw instanceof String objectId && !objectId.isBlank()) {
            return Map.of("objectId", objectId);
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }

        Map<String, Object> attachment = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() != null) {
                attachment.put(key, entry.getValue());
            }
        }

        String objectId = firstNonBlank(
                stringValue(attachment.get("objectId")),
                stringValue(attachment.get("sourceObjectId")));
        if (objectId == null) {
            return Map.of();
        }
        attachment.put("objectId", objectId);
        return attachment;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Map<String, Object> findLatestFinalClarifyResult(VerlaTurn turn) {
        List<VerlaSession> sessions = sessionRepository.findByTurn(turn.getId());
        for (int i = sessions.size() - 1; i >= 0; i--) {
            VerlaSession session = sessions.get(i);
            if (!VerlaSessionKind.ASSIGNMENT.name().equals(session.getKind())) {
                continue;
            }
            Map<String, Object> result = parseSlotsJson(session.getResultJson());
            if (Boolean.TRUE.equals(result.get("isReadyForGeneration"))) {
                return result;
            }
        }
        return Map.of();
    }

    private static boolean isAssignmentIntent(String intent) {
        if (intent == null) {
            return false;
        }
        String normalized = intent.trim()
                .toUpperCase()
                .replace('-', '_')
                .replace(' ', '_');
        return "ASSIGNMENT".equals(normalized) || "CREATE_ASSIGNMENT".equals(normalized);
    }

    private static boolean isAiDetectionIntent(String intent) {
        if (intent == null) {
            return false;
        }
        String normalized = intent.trim()
                .toUpperCase()
                .replace('-', '_')
                .replace(' ', '_');
        return "AI_DETECTION".equals(normalized);
    }

    private static boolean isAiHumanizerIntent(String intent) {
        if (intent == null) {
            return false;
        }
        String normalized = intent.trim()
                .toUpperCase()
                .replace('-', '_')
                .replace(' ', '_');
        return "AI_HUMANIZER".equals(normalized);
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

    private static Map<String, Object> normalizeDeepUnderstandingResult(Map<String, Object> result) {
        if (result == null) {
            return Map.of();
        }
        if (result.get("ready") instanceof Boolean) {
            return result;
        }
        Boolean ready = coerceBoolean(result.get("ready"));
        if (ready != null) {
            result.put("ready", ready);
        }
        return result;
    }

    private static Map<String, Object> withoutTopLevelStage(Map<String, Object> result) {
        if (result == null || result.isEmpty() || !result.containsKey("stage")) {
            return result;
        }
        Map<String, Object> sanitized = new HashMap<>(result);
        sanitized.remove("stage");
        return sanitized;
    }

    private static Map<String, Object> withEventTypeWithoutStage(
            Map<String, Object> result, String eventType) {
        Map<String, Object> sanitized = result == null ? new HashMap<>() : new HashMap<>(result);
        sanitized.remove("stage");
        sanitized.putIfAbsent("eventType", eventType);
        return sanitized;
    }

    private static Map<String, Object> withEventTypeWithoutStageForFrontend(
            Map<String, Object> result, String eventType) {
        return VerlaFrontendPayloadSanitizer.sanitize(eventType, withEventTypeWithoutStage(result, eventType));
    }

    private static Boolean coerceBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if (List.of("yes", "true", "1").contains(normalized)) {
                return true;
            }
            if (List.of("no", "false", "0").contains(normalized)) {
                return false;
            }
        }
        return null;
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
                if ("finalResult".equals(key) && isLargeGeneratedArtifactResult(result, s)) {
                    return GENERATED_ARTIFACT_READY_TEXT;
                }
                return truncateAssistantTextContent(s);
            }
        }
        return null;
    }

    private static String extractFileChatAssistantReply(Map<String, Object> result) {
        if (result != null) {
            Object finalText = result.get("finalText");
            if (finalText instanceof String text && !text.isBlank()) {
                return truncateAssistantTextContent(text);
            }
        }
        return extractAssistantReply(result);
    }

    private String resolveFileChatObjectId(VerlaTurn turn, Map<String, Object> result) {
        String fromPayload = stringValue(result == null ? null : result.get("objectId"));
        if (fromPayload != null && !fromPayload.isBlank()) {
            return fromPayload;
        }
        if (turn != null && turn.getUserMessageId() != null) {
            VerlaMessage userMessage = messageRepository.findById(turn.getUserMessageId());
            FileChatMessageMeta meta = userMessage == null ? null
                    : VerlaFileChatMetadataHelper.readMessageMeta(userMessage.getMetaJson());
            if (meta != null && meta.getObjectId() != null && !meta.getObjectId().isBlank()) {
                return meta.getObjectId();
            }
        }
        if (turn == null) {
            return null;
        }
        return stringValue(parseSlotsJson(turn.getResolvedSlotsJson()).get("objectId"));
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static Map<String, Object> sanitizeAssistantBlocks(Map<String, Object> result) {
        Map<String, Object> sanitized = withoutTopLevelStage(result);
        if (sanitized == null || sanitized.isEmpty()) {
            return sanitized;
        }
        Object finalResult = sanitized.get("finalResult");
        if (!(finalResult instanceof String text)
                || !isLargeGeneratedArtifactResult(sanitized, text)) {
            return sanitized;
        }

        sanitized = new HashMap<>(sanitized);
        sanitized.put("finalResult", GENERATED_ARTIFACT_READY_TEXT);
        sanitized.put("finalResultTruncated", true);
        sanitized.put("finalResultLength", text.length());
        return sanitized;
    }

    private static boolean isLargeGeneratedArtifactResult(Map<String, Object> result, String value) {
        if (value == null || value.length() <= MAX_ASSISTANT_TEXT_CONTENT_CHARS) {
            return false;
        }
        return result != null && (result.containsKey("artifactPaths")
                || result.containsKey("primaryArtifactUid")
                || result.containsKey("artifactUids"));
    }

    private static String truncateAssistantTextContent(String value) {
        if (value == null || value.length() <= MAX_ASSISTANT_TEXT_CONTENT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_ASSISTANT_TEXT_CONTENT_CHARS)
                + "\n\n[Truncated. View the generated artifact for the full result.]";
    }

    private static String extractClarifyText(Map<String, Object> clarifyBlock) {
        if (clarifyBlock == null) {
            return null;
        }
        Object q = clarifyBlock.get("question");
        return q == null ? null : q.toString();
    }

    private static String normalizeClarifyChoice(String userChoice) {
        if (userChoice == null || userChoice.isBlank()) {
            return null;
        }
        if ("generateion".equals(userChoice)) {
            return "generation";
        }
        if ("deep_understanding".equals(userChoice) || "generation".equals(userChoice)) {
            return userChoice;
        }
        return null;
    }

    private String buildClarifyUserMessageText(String userChoice, String text,
                                               Map<String, Object> reservedFields,
                                               List<Map<String, Object>> appendAskAnswers) {
        return buildClarifyUserMessageText(userChoice, text, reservedFields, appendAskAnswers, null);
    }

    private static String buildContinueClarifyUserMessageText(String normalizedChoice, String text) {
        if ("generation".equals(normalizedChoice)) {
            return ASSIGNMENT_START_YES_TEXT;
        }
        if ("deep_understanding".equals(normalizedChoice) && "no".equalsIgnoreCase(text)) {
            return ASSIGNMENT_START_NO_TEXT;
        }
        return text == null ? "" : text;
    }

    private String buildClarifyUserMessageText(String userChoice, String text,
                                               Map<String, Object> reservedFields,
                                               List<Map<String, Object>> appendAskAnswers,
                                               Map<String, Object> requirementForm) {
        StringBuilder sb = new StringBuilder();

        // requirementForm is the primary form data; fall back to reservedFields when absent
        Map<String, Object> formFields = (requirementForm != null && !requirementForm.isEmpty())
                ? requirementForm : reservedFields;

        if (formFields != null && !formFields.isEmpty()) {
            sb.append("[Assignment Details]\n");
            formFields.forEach((key, value) -> {
                String v = (value == null || value.toString().isBlank()) ? "" : value.toString();
                sb.append(toFieldLabel(key)).append(": ").append(v).append("\n");
            });
        }

        if (appendAskAnswers != null && !appendAskAnswers.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("[Follow-up Answers]\n");
            for (int i = 0; i < appendAskAnswers.size(); i++) {
                Map<String, Object> qa = appendAskAnswers.get(i);
                String question = String.valueOf(qa.getOrDefault("question", "")).trim();
                String answer = String.valueOf(qa.getOrDefault("answer", "")).trim();
                if (!question.isEmpty() || !answer.isEmpty()) {
                    sb.append("Q: ").append(question).append("\n");
                    sb.append("A: ").append(answer.isEmpty() ? "(no answer)" : answer).append("\n");
                    if (i < appendAskAnswers.size() - 1) sb.append("\n");
                }
            }
        }

        if (sb.isEmpty()) {
            return "Continue assignment generation.";
        }
        return sb.toString().trim();
    }

    private static List<Map<String, Object>> normalizeAppendAskAnswers(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        return raw.stream().map(qa -> {
            Map<String, Object> normalized = new HashMap<>(qa);
            if (!normalized.containsKey("answer") || normalized.get("answer") == null) {
                normalized.put("answer", "");
            }
            return normalized;
        }).toList();
    }

    private static String toFieldLabel(String fieldId) {
        if (fieldId == null || fieldId.isBlank()) return fieldId;
        return Arrays.stream(fieldId.split("[_\\-]"))
                .filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }

    private static Map<String, Object> buildContextRef(String endpoint, Long convVersion) {
        Map<String, Object> ref = new HashMap<>();
        ref.put("type", "internal-rpc");
        ref.put("endpoint", endpoint);
        if (convVersion != null) {
            ref.put("convVersion", convVersion);
        }
        return ref;
    }

    private static void refreshConversationVersion(VerlaConversation conv, Long latestVersion) {
        if (conv != null && latestVersion != null) {
            conv.setVersion(latestVersion);
        }
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
