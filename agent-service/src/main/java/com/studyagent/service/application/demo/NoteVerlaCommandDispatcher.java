package com.studyagent.service.application.demo;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
import com.studyagent.common.verla.envelope.VerlaConversationRef;
import com.studyagent.common.verla.envelope.VerlaProducerInfo;
import com.studyagent.common.verla.envelope.VerlaSessionRef;
import com.studyagent.common.verla.envelope.VerlaTurnRef;
import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.common.verla.enums.VerlaSessionKind;
import com.studyagent.common.verla.util.VerlaCorrelationId;
import com.studyagent.service.application.MqOutboxService;
import com.studyagent.service.application.demo.dto.NoteGenerateDispatchResult;
import com.studyagent.service.application.demo.dto.NoteTurnContext;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.quota.NoteInfographicReservation;
import com.studyagent.service.application.verla.quota.VerlaQuotaContext;
import com.studyagent.service.application.verla.quota.VerlaQuotaService;
import com.studyagent.service.domain.demo.note.NoteConversation;
import com.studyagent.service.domain.demo.note.repo.DemoNoteRepository;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import com.studyagent.service.domain.verla.state.SessionEvent;
import com.studyagent.service.domain.verla.state.SessionStateMachine;
import com.studyagent.service.domain.verla.state.SessionStatus;
import com.studyagent.service.domain.verla.state.TurnStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import com.studyagent.common.analytics.AnalyticsEvents;
import com.studyagent.common.analytics.AnalyticsService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 笔记生成的命令派发器：建立主线 turn / session 真行，并把 {@code cmd.note.generate}
 * 写入事务性 outbox。
 * <p>位于 agent-service 而非 agent-infra：{@link MqOutboxService#createVerlaCommand} 是
 * {@code REQUIRED} 传播，必须与 turn / session 落库处于同一事务，提交后才由
 * OutboxImmediateDispatcher 发送，避免 Python 先于 Java 事务可见地回投事件。
 * <p>会话 kind = NOTE，featureCode = demo_note_maker（纯免费记账）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteVerlaCommandDispatcher {

    private static final String PRODUCER_SERVICE = "java-agent-service";
    private static final String INSTANCE_ID = resolveHostname();
    private static final String DEFAULT_COMMAND_EXCHANGE = "studyagent.command";

    @Value("${verla.mq.command-exchange:" + DEFAULT_COMMAND_EXCHANGE + "}")
    private String commandExchange;

    private final VerlaConversationRepository conversationRepository;
    private final VerlaTurnRepository turnRepository;
    private final VerlaSessionRepository sessionRepository;
    private final SessionStateMachine sessionStateMachine;
    private final VerlaConversationService conversationService;
    private final MqOutboxService mqOutboxService;
    private final VerlaQuotaService verlaQuotaService;
    private final DemoNoteRepository noteRepository;
    private final AnalyticsService analyticsService;

    /**
     * 派发一轮笔记回合。调用方须已把素材/消息落库
     * （{@code DemoNoteService.prepareFirstTurn / prepareFollowUpTurn}）。
     *
     * @param noteConv    demo 会话（{@code verlaConversationId} 必填）
     * @param turnContext 本轮上下文：首轮标志、用户消息、编辑器基线、history、输出语言
     */
    @Transactional
    public NoteGenerateDispatchResult dispatch(NoteConversation noteConv, NoteTurnContext turnContext) {
        NoteTurnContext ctx = turnContext == null ? NoteTurnContext.builder().build() : turnContext;
        Long verlaConversationId = noteConv.getVerlaConversationId();
        if (verlaConversationId == null) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(), "会话尚未接入主线事件通道，请重新创建会话");
        }
        VerlaConversation verlaConv = conversationRepository.findById(verlaConversationId);
        if (verlaConv == null) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(),
                    "主线会话不存在: " + verlaConversationId);
        }
        String outputLanguage = ctx.getOutputLanguage();
        if (outputLanguage != null && !outputLanguage.isBlank()) {
            verlaConv = conversationService.setOutputLanguage(verlaConv, outputLanguage);
        }

        LocalDateTime now = DateTimeFormats.now();

        VerlaTurn turn = VerlaTurn.builder()
                .conversationId(verlaConversationId)
                .status(TurnStatus.CREATED.name())
                .completedSteps(0)
                .startedAt(now)
                .lastProgressAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        turnRepository.save(turn);
        conversationRepository.touchOnNewTurn(verlaConversationId, turn.getId());

        // correlation_id 由三元组构成且带唯一约束，必须先 save 拿到自增 sessionId 再回填。
        VerlaSession session = VerlaSession.builder()
                .conversationId(verlaConversationId)
                .turnId(turn.getId())
                .kind(VerlaSessionKind.NOTE.name())
                .featureCode(FeatureCode.DEMO_NOTE_MAKER.getCode())
                .status(SessionStatus.CREATED.name())
                .correlationId("placeholder")
                .expectedSeq(1L)
                .lastEventSeq(0L)
                .startedAt(now)
                .lastProgressAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        sessionRepository.save(session);
        session.setCorrelationId(
                VerlaCorrelationId.of(verlaConversationId, turn.getId(), session.getId()));

        SessionStatus dispatching = sessionStateMachine.next(SessionStatus.CREATED, SessionEvent.DISPATCH);
        session.setStatus(dispatching.name());
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(session);

        // 商业化：笔记正文/导图/播客/测验不限次；只有知识图按张预占 1 次。
        // 余额不足不阻断笔记，只把图片状态置为 QUOTA_EXHAUSTED，由前端引导升级。
        if (ctx.isFirstTurn()) {
            NoteInfographicReservation reservation = verlaQuotaService.reserveForNoteInfographic(
                    VerlaQuotaContext.builder()
                            .clerkUserId(noteConv.getClerkUserId())
                            .conversationId(verlaConversationId)
                            .turnId(turn.getId())
                            .sessionId(session.getId())
                            .intent("NOTE_MAKER")
                            .build());
            noteConv.setInfographicStatus(reservation.allowed()
                    ? DemoNoteService.INFOGRAPHIC_STATUS_GENERATING
                    : DemoNoteService.INFOGRAPHIC_STATUS_QUOTA_EXHAUSTED);
            noteRepository.saveConversation(noteConv);
        }

        VerlaCommandEnvelope envelope = VerlaCommandEnvelope.builder()
                .schemaVersion(1)
                .messageId("cmd-" + UUID.randomUUID())
                .correlationId(session.getCorrelationId())
                .orderingKey(VerlaCorrelationId.orderingKey(session.getId()))
                .action(VerlaCommandAction.CMD_NOTE_GENERATE.getCode())
                .timestamp(Instant.now())
                .producer(VerlaProducerInfo.builder()
                        .service(PRODUCER_SERVICE)
                        .instanceId(INSTANCE_ID)
                        .build())
                .conversation(VerlaConversationRef.builder()
                        .conversationId(verlaConversationId)
                        .userId(verlaConv.getUserId())
                        .build())
                .turn(VerlaTurnRef.builder()
                        .turnId(turn.getId())
                        .build())
                .session(VerlaSessionRef.builder()
                        .sessionId(session.getId())
                        .kind(VerlaSessionKind.NOTE)
                        .feature(session.getFeatureCode())
                        .build())
                .payload(buildPayload(noteConv, verlaConv, ctx))
                .build();

        mqOutboxService.createVerlaCommand(
                envelope, commandExchange, VerlaCommandAction.CMD_NOTE_GENERATE.getCode());

        Map<String, Object> analyticsProps = new HashMap<>();
        analyticsProps.put("task_type", "note");
        analyticsProps.put("conversation_id", verlaConversationId);
        analyticsProps.put("note_conversation_id", noteConv.getId());
        analyticsProps.put("turn_id", turn.getId());
        analyticsProps.put("session_id", session.getId());
        if (noteConv.getSourceType() != null) {
            analyticsProps.put("source_type", noteConv.getSourceType());
        }
        analyticsProps.put("first_turn", ctx.isFirstTurn());
        analyticsProps.put("has_file", noteConv.getAttachmentObjectId() != null);
        if (ctx.getOutputLanguage() != null) {
            analyticsProps.put("output_language", ctx.getOutputLanguage());
        }
        analyticsService.capture(
                noteConv.getClerkUserId(), AnalyticsEvents.NOTE_GENERATION_STARTED, analyticsProps);

        log.info("[Notes] dispatched cmd.note.generate noteConvId={}, verlaConvId={}, turnId={}, sessionId={}, "
                        + "firstTurn={}, historyMessages={}",
                noteConv.getId(), verlaConversationId, turn.getId(), session.getId(),
                ctx.isFirstTurn(), ctx.getHistory() == null ? 0 : ctx.getHistory().size());

        return NoteGenerateDispatchResult.builder()
                .verlaConversationId(verlaConversationId)
                .turnId(turn.getId())
                .sessionId(session.getId())
                .correlationId(session.getCorrelationId())
                .build();
    }

    /**
     * 知识图单图重试：只重跑图片，不重写笔记、不追加聊天消息。
     * <p>复用 {@code cmd.note.generate} 动作以避免新增 MQ 路由；payload.mode 让 Python
     * 直接进入 infographic_only 分支。额度预占失败时抛 InsufficientQuotaException，
     * 前端据此打开会员升级弹窗。
     */
    @Transactional
    public NoteGenerateDispatchResult dispatchInfographicRetry(NoteConversation noteConv, String requestId) {
        if (noteConv == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "会话不存在");
        }
        if (noteConv.getNoteMd() == null || noteConv.getNoteMd().isBlank()) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(), "笔记正文为空，无法生成知识图");
        }
        String currentStatus = noteConv.getInfographicStatus();
        if (DemoNoteService.INFOGRAPHIC_STATUS_GENERATING.equals(currentStatus)) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(), "知识图正在生成，请稍候");
        }
        if (DemoNoteService.INFOGRAPHIC_STATUS_READY.equals(currentStatus)) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(), "知识图已生成");
        }

        Long verlaConversationId = noteConv.getVerlaConversationId();
        if (verlaConversationId == null) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(), "会话尚未接入主线事件通道");
        }
        VerlaConversation verlaConv = conversationRepository.findById(verlaConversationId);
        if (verlaConv == null) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(),
                    "主线会话不存在: " + verlaConversationId);
        }

        LocalDateTime now = DateTimeFormats.now();
        VerlaTurn turn = VerlaTurn.builder()
                .conversationId(verlaConversationId)
                .status(TurnStatus.CREATED.name())
                .completedSteps(0)
                .startedAt(now)
                .lastProgressAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        turnRepository.save(turn);
        conversationRepository.touchOnNewTurn(verlaConversationId, turn.getId());

        VerlaSession session = VerlaSession.builder()
                .conversationId(verlaConversationId)
                .turnId(turn.getId())
                .kind(VerlaSessionKind.NOTE.name())
                .featureCode(FeatureCode.DEMO_NOTE_MAKER.getCode())
                .status(SessionStatus.CREATED.name())
                .correlationId("placeholder")
                .expectedSeq(1L)
                .lastEventSeq(0L)
                .startedAt(now)
                .lastProgressAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        sessionRepository.save(session);
        session.setCorrelationId(
                VerlaCorrelationId.of(verlaConversationId, turn.getId(), session.getId()));
        SessionStatus dispatching = sessionStateMachine.next(SessionStatus.CREATED, SessionEvent.DISPATCH);
        session.setStatus(dispatching.name());
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(session);

        String retryId = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId.trim();
        verlaQuotaService.reserveForNoteInfographic(
                VerlaQuotaContext.builder()
                        .clerkUserId(noteConv.getClerkUserId())
                        .conversationId(verlaConversationId)
                        .turnId(turn.getId())
                        .sessionId(session.getId())
                        .intent("NOTE_MAKER_INFOGRAPHIC_RETRY")
                        .requestId(retryId)
                        .build(),
                true);

        noteConv.setInfographicStatus(DemoNoteService.INFOGRAPHIC_STATUS_GENERATING);
        noteConv.setInfographicUrl(null);
        noteConv.setUpdatedAt(LocalDateTime.now());
        noteRepository.saveConversation(noteConv);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "infographic_only");
        payload.put("noteMd", noteConv.getNoteMd());
        payload.put("title", noteConv.getTitle());
        payload.put("outputLanguage", conversationService.resolveOutputLanguage(verlaConv));
        payload.put("infographicStyle",
                noteConv.getInfographicStyle() == null || noteConv.getInfographicStyle().isBlank()
                        ? DemoNoteService.INFOGRAPHIC_STYLE_CLASSIC : noteConv.getInfographicStyle());
        payload.put("noteConversationId", noteConv.getId());
        payload.put("requestId", retryId);

        VerlaCommandEnvelope envelope = VerlaCommandEnvelope.builder()
                .schemaVersion(1)
                .messageId("cmd-" + UUID.randomUUID())
                .correlationId(session.getCorrelationId())
                .orderingKey(VerlaCorrelationId.orderingKey(session.getId()))
                .action(VerlaCommandAction.CMD_NOTE_GENERATE.getCode())
                .timestamp(Instant.now())
                .producer(VerlaProducerInfo.builder()
                        .service(PRODUCER_SERVICE)
                        .instanceId(INSTANCE_ID)
                        .build())
                .conversation(VerlaConversationRef.builder()
                        .conversationId(verlaConversationId)
                        .userId(verlaConv.getUserId())
                        .build())
                .turn(VerlaTurnRef.builder()
                        .turnId(turn.getId())
                        .build())
                .session(VerlaSessionRef.builder()
                        .sessionId(session.getId())
                        .kind(VerlaSessionKind.NOTE)
                        .feature(session.getFeatureCode())
                        .build())
                .payload(payload)
                .build();

        mqOutboxService.createVerlaCommand(
                envelope, commandExchange, VerlaCommandAction.CMD_NOTE_GENERATE.getCode());

        Map<String, Object> analyticsProps = new HashMap<>();
        analyticsProps.put("task_type", "note");
        analyticsProps.put("conversation_id", verlaConversationId);
        analyticsProps.put("note_conversation_id", noteConv.getId());
        analyticsProps.put("turn_id", turn.getId());
        analyticsProps.put("session_id", session.getId());
        analyticsProps.put("mode", "infographic_only");
        analyticsService.capture(
                noteConv.getClerkUserId(), AnalyticsEvents.NOTE_GENERATION_STARTED, analyticsProps);

        log.info("[Notes] dispatched infographic retry noteConvId={}, verlaConvId={}, turnId={}, sessionId={}, requestId={}",
                noteConv.getId(), verlaConversationId, turn.getId(), session.getId(), retryId);

        return NoteGenerateDispatchResult.builder()
                .verlaConversationId(verlaConversationId)
                .turnId(turn.getId())
                .sessionId(session.getId())
                .correlationId(session.getCorrelationId())
                .build();
    }

    /**
     * payload 字段是 Java / Python 的隐式契约，Python 侧 {@code NoteService} 直接按 key 取值：
     * <ul>
     *   <li>{@code message} —— 本轮用户说了什么（首轮 = 表单 query，可空；后续轮必填）</li>
     *   <li>{@code documentContentMd} —— 编辑器当前基线（后续轮前端 flushSave 后带上）</li>
     *   <li>{@code documentBaseVersion} —— 编辑器持有的版本号（乐观锁基线，首轮为 0）</li>
     *   <li>{@code history} —— 最近若干轮 {@code {role,content}}（已按条数 + 字节双预算裁剪）</li>
     *   <li>{@code outputLanguage} —— 取自 workspace_json，缺省 english</li>
     *   <li>{@code courseName} / {@code learningGoal} —— 从会话行取（首轮由表单落库），每轮都下发</li>
     *   <li>{@code noteConversationId} —— demo 主键，仅排障用（事件回填按主线 id 反查）</li>
     *   <li><b>仅首轮</b>：{@code sourceType} / {@code objectId} / {@code inputText} / {@code filename}</li>
     * </ul>
     * <p><b>素材字段只在首轮下发</b>：Python 以「payload 是否带 objectId/inputText」判定首轮，
     * 若后续轮也带上，会重复解析素材并把这一轮当成首轮重写整篇笔记。
     */
    private Map<String, Object> buildPayload(NoteConversation noteConv, VerlaConversation verlaConv,
                                             NoteTurnContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", blankToNull(ctx.getMessage()));
        payload.put("documentContentMd", ctx.getDocumentContentMd() == null ? "" : ctx.getDocumentContentMd());
        payload.put("documentBaseVersion",
                ctx.getDocumentBaseVersion() == null ? 0L : ctx.getDocumentBaseVersion());
        payload.put("history", ctx.getHistory() == null ? List.of() : ctx.getHistory());
        // 用户显式引用的历史笔记（已做归属校验与双预算裁剪）；空列表也要下发，
        // 让 Python 侧「有没有引用」始终是一个确定的值。
        payload.put("referencedNotes",
                ctx.getReferencedNotes() == null ? List.of() : ctx.getReferencedNotes());
        // 联网开关：true 时 Python 才挂 search_wiki / search_serper
        payload.put("webSearch", ctx.isWebSearch());
        payload.put("outputLanguage", conversationService.resolveOutputLanguage(verlaConv));
        // 课程名/学习目标是会话级设置：首轮落库后每轮都从会话行下发，后续轮不必再传表单
        payload.put("courseName", noteConv.getCourseName());
        payload.put("learningGoal", noteConv.getLearningGoal());
        payload.put("infographicStyle",
                noteConv.getInfographicStyle() == null || noteConv.getInfographicStyle().isBlank()
                        ? DemoNoteService.INFOGRAPHIC_STYLE_CLASSIC : noteConv.getInfographicStyle());
        // 首轮才生成知识图；额度不足时 Python 只跳过图片，其他 extras 照常。
        payload.put("infographicEnabled", ctx.isFirstTurn()
                && !DemoNoteService.INFOGRAPHIC_STATUS_QUOTA_EXHAUSTED.equals(noteConv.getInfographicStatus()));
        payload.put("noteConversationId", noteConv.getId());
        if (ctx.isFirstTurn()) {
            payload.put("sourceType", noteConv.getSourceType());
            payload.put("objectId", noteConv.getAttachmentObjectId());
            payload.put("inputText", noteConv.getInputText());
            // 只有文件来源的 title 才是真实文件名；文本来源的 title 是正文片段，不能当来源名渲染。
            payload.put("filename",
                    DemoNoteService.SOURCE_TYPE_FILE.equals(noteConv.getSourceType())
                            ? noteConv.getTitle() : null);
        }
        return payload;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
