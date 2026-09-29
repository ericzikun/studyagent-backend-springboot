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
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.domain.demo.note.NoteConversation;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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

    /**
     * 派发一次笔记生成。调用方须已把素材落库（{@code DemoNoteService.prepareGeneration}）。
     *
     * @param noteConv       demo 会话（{@code verlaConversationId} 必填）
     * @param outputLanguage 本轮请求携带的输出语言（可空；非空时会覆盖会话偏好）
     */
    @Transactional
    public NoteGenerateDispatchResult dispatch(NoteConversation noteConv, String outputLanguage) {
        Long verlaConversationId = noteConv.getVerlaConversationId();
        if (verlaConversationId == null) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(), "会话尚未接入主线事件通道，请重新创建会话");
        }
        VerlaConversation verlaConv = conversationRepository.findById(verlaConversationId);
        if (verlaConv == null) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(),
                    "主线会话不存在: " + verlaConversationId);
        }
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
                .payload(buildPayload(noteConv, verlaConv))
                .build();

        mqOutboxService.createVerlaCommand(
                envelope, commandExchange, VerlaCommandAction.CMD_NOTE_GENERATE.getCode());

        log.info("[Notes] dispatched cmd.note.generate noteConvId={}, verlaConvId={}, turnId={}, sessionId={}, sourceType={}",
                noteConv.getId(), verlaConversationId, turn.getId(), session.getId(), noteConv.getSourceType());

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
     *   <li>{@code sourceType} —— {@code file} / {@code text}</li>
     *   <li>{@code objectId} —— file 时的附件 id（Python 用既有 FileService 解析）</li>
     *   <li>{@code inputText} —— text 时的原始素材</li>
     *   <li>{@code filename} —— 展示用的来源名</li>
     *   <li>{@code outputLanguage} —— 取自 workspace_json，缺省 english</li>
     *   <li>{@code noteConversationId} —— demo 主键，仅排障用（事件回填按主线 id 反查）</li>
     * </ul>
     */
    private Map<String, Object> buildPayload(NoteConversation noteConv, VerlaConversation verlaConv) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceType", noteConv.getSourceType());
        payload.put("objectId", noteConv.getAttachmentObjectId());
        payload.put("inputText", noteConv.getInputText());
        // 只有文件来源的 title 才是真实文件名；文本来源的 title 是正文片段，不能当来源名渲染。
        payload.put("filename",
                DemoNoteService.SOURCE_TYPE_FILE.equals(noteConv.getSourceType())
                        ? noteConv.getTitle() : null);
        payload.put("outputLanguage", conversationService.resolveOutputLanguage(verlaConv));
        payload.put("noteConversationId", noteConv.getId());
        return payload;
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
