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
import com.studyagent.service.application.demo.dto.AiTutorChatDispatchResult;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.domain.demo.aitutor.AiTutorConversation;
import com.studyagent.service.domain.demo.aitutor.AiTutorDocument;
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
 * AI Tutor demo 的命令派发器：为每一轮对话建立主线 turn / session 真行，
 * 并把 {@code cmd.aitutor.chat} 写入事务性 outbox。
 * <p>
 * 位于 agent-service 而非 agent-infra：{@link MqOutboxService#createVerlaCommand} 是
 * {@code REQUIRED} 传播，必须与 turn / session 落库处于同一事务，提交后才由
 * OutboxImmediateDispatcher 发送，避免 Python 先于 Java 事务可见地回投事件。
 * <p>
 * 每轮新建一个 session（而非复用）：事件按 sessionId 全局串行，一旦某轮丢事件，
 * 同 session 的后续所有轮次都会卡在 inbox 的 "early event held" 分支；按轮切分把
 * 丢失影响面限制在单轮。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTutorVerlaCommandDispatcher {

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
     * 派发一轮 AI Tutor 对话。调用方须已把 user 消息落库，并保证 demo 会话已绑定主线会话。
     *
     * @param demoConv demo 会话（{@code verlaConversationId} 必填）
     * @param document 当前文档基线，可为 null（首轮尚未产出）
     * @param message  本轮用户输入（已 trim）
     */
    @Transactional
    public AiTutorChatDispatchResult dispatch(AiTutorConversation demoConv,
                                              AiTutorDocument document,
                                              String message) {
        Long verlaConversationId = demoConv.getVerlaConversationId();
        if (verlaConversationId == null) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE, "会话尚未接入主线事件通道，请重新创建会话");
        }
        VerlaConversation verlaConv = conversationRepository.findById(verlaConversationId);
        if (verlaConv == null) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE,
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

        // correlation_id 由三元组构成且带唯一约束，必须先 save 拿到自增 sessionId 再回填。
        VerlaSession session = VerlaSession.builder()
                .conversationId(verlaConversationId)
                .turnId(turn.getId())
                .kind(VerlaSessionKind.AITUTOR.name())
                .featureCode(FeatureCode.DEMO_AI_TUTOR.getCode())
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
                .action(VerlaCommandAction.CMD_AITUTOR_CHAT.getCode())
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
                        .kind(VerlaSessionKind.AITUTOR)
                        .feature(session.getFeatureCode())
                        .build())
                .payload(buildPayload(demoConv, verlaConv, document, message))
                .build();

        mqOutboxService.createVerlaCommand(
                envelope, commandExchange, VerlaCommandAction.CMD_AITUTOR_CHAT.getCode());

        log.info("[AI-Tutor] dispatched cmd.aitutor.chat demoConvId={}, verlaConvId={}, turnId={}, sessionId={}",
                demoConv.getId(), verlaConversationId, turn.getId(), session.getId());

        return AiTutorChatDispatchResult.builder()
                .verlaConversationId(verlaConversationId)
                .turnId(turn.getId())
                .sessionId(session.getId())
                .correlationId(session.getCorrelationId())
                .build();
    }

    /**
     * payload 字段是 Java / Python 的隐式契约，Python 侧 {@code AiTutorFlowService} 直接按 key 取值：
     * <ul>
     *   <li>{@code documentBaseVersion} —— Python 回显 versionNo 用；真实版本号仍以 Java 落库结果为准</li>
     *   <li>{@code demoConversationId} —— 事件 handler 反查 demo 表用，刻意不复用 {@code conversationId}
     *       这个名字，避免与主线 verla conversationId 混淆</li>
     *   <li>{@code outputLanguage} —— 取自 workspace_json，缺省 english</li>
     * </ul>
     */
    private Map<String, Object> buildPayload(AiTutorConversation demoConv,
                                             VerlaConversation verlaConv,
                                             AiTutorDocument document,
                                             String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("paperTitle", demoConv.getTitle() == null ? "" : demoConv.getTitle());
        payload.put("paperMeta", demoConv.getPaperMeta());
        payload.put("documentContentMd", document == null || document.getContentMd() == null
                ? "" : document.getContentMd());
        payload.put("documentBaseVersion", document == null || document.getBaseVersion() == null
                ? 0L : document.getBaseVersion());
        payload.put("outputLanguage", conversationService.resolveOutputLanguage(verlaConv));
        payload.put("demoConversationId", demoConv.getId());
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
