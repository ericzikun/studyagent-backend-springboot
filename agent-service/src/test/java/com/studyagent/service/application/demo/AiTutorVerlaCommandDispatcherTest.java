package com.studyagent.service.application.demo;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
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
import com.studyagent.service.domain.verla.state.SessionStateMachine;
import com.studyagent.service.domain.verla.state.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 派发器的契约测试。
 * <p>
 * 重点不是「调没调」，而是三件在 demo 里出过事的约定：
 * <ol>
 *   <li>correlationId 必须先 save 拿自增 id 再回填，不能留着 {@code placeholder}（uk_correlation 唯一约束）；</li>
 *   <li>命令必须走 {@code MqOutboxService}（事务性 outbox），而不是裸发——裸发没有 publisher confirm，
 *       NO_ROUTE 会被静默丢弃；</li>
 *   <li>payload 的三个 key（{@code documentBaseVersion} / {@code outputLanguage} / {@code demoConversationId}）
 *       是 Java → Python 的隐式契约，Python 侧按 key 直接取值。</li>
 * </ol>
 */
class AiTutorVerlaCommandDispatcherTest {

    private static final long VERLA_CONVERSATION_ID = 11L;
    private static final long TURN_ID = 22L;
    private static final long SESSION_ID = 33L;
    private static final long DEMO_CONVERSATION_ID = 44L;
    private static final String COMMAND_EXCHANGE = "studyagent.command";

    private VerlaConversationRepository conversationRepository;
    private VerlaTurnRepository turnRepository;
    private VerlaSessionRepository sessionRepository;
    private VerlaConversationService conversationService;
    private MqOutboxService mqOutboxService;
    private AiTutorVerlaCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(VerlaConversationRepository.class);
        turnRepository = mock(VerlaTurnRepository.class);
        sessionRepository = mock(VerlaSessionRepository.class);
        conversationService = mock(VerlaConversationService.class);
        mqOutboxService = mock(MqOutboxService.class);

        dispatcher = new AiTutorVerlaCommandDispatcher(
                conversationRepository,
                turnRepository,
                sessionRepository,
                new SessionStateMachine(),
                conversationService,
                mqOutboxService);
        ReflectionTestUtils.setField(dispatcher, "commandExchange", COMMAND_EXCHANGE);

        // save 负责分配自增主键，模拟真实仓储必须回填 id，否则 correlationId 无法回填。
        when(turnRepository.save(any(VerlaTurn.class))).thenAnswer(invocation -> {
            VerlaTurn turn = invocation.getArgument(0);
            turn.setId(TURN_ID);
            return turn;
        });
        when(sessionRepository.save(any(VerlaSession.class))).thenAnswer(invocation -> {
            VerlaSession session = invocation.getArgument(0);
            session.setId(SESSION_ID);
            return session;
        });
    }

    @Test
    void dispatch_creates_mainline_rows_and_enqueues_transactional_outbox() {
        AiTutorConversation demoConversation = demoConversation();
        AiTutorDocument document = new AiTutorDocument();
        document.setConversationId(DEMO_CONVERSATION_ID);
        document.setContentMd("# 引言\n初稿");
        document.setBaseVersion(2L);
        VerlaConversation verlaConversation = verlaConversation();
        when(conversationRepository.findById(VERLA_CONVERSATION_ID)).thenReturn(verlaConversation);
        when(conversationService.resolveOutputLanguage(verlaConversation)).thenReturn("chinese");

        AiTutorChatDispatchResult result = dispatcher.dispatch(demoConversation, document, "帮我写第二章");

        ArgumentCaptor<VerlaTurn> turnCaptor = ArgumentCaptor.forClass(VerlaTurn.class);
        verify(turnRepository).save(turnCaptor.capture());
        VerlaTurn turn = turnCaptor.getValue();
        assertEquals(VERLA_CONVERSATION_ID, turn.getConversationId());
        assertEquals(SessionStatus.CREATED.name(), turn.getStatus());
        verify(conversationRepository).touchOnNewTurn(VERLA_CONVERSATION_ID, TURN_ID);

        ArgumentCaptor<VerlaSession> sessionCaptor = ArgumentCaptor.forClass(VerlaSession.class);
        verify(sessionRepository, org.mockito.Mockito.times(2)).save(sessionCaptor.capture());
        VerlaSession session = sessionCaptor.getValue();
        assertEquals(VerlaSessionKind.AITUTOR.name(), session.getKind());
        assertEquals(FeatureCode.DEMO_AI_TUTOR.getCode(), session.getFeatureCode());
        // DISPATCH 转换，不是 CREATED；Python 侧靠 AGENT_STARTED 再推到 RUNNING。
        assertEquals(SessionStatus.DISPATCHING.name(), session.getStatus());
        // 关键：不能停在 placeholder，否则 uk_correlation 冲突 / 排障日志对不上。
        assertNotEquals("placeholder", session.getCorrelationId());
        assertEquals(VerlaCorrelationId.of(VERLA_CONVERSATION_ID, TURN_ID, SESSION_ID),
                session.getCorrelationId());

        ArgumentCaptor<VerlaCommandEnvelope> envelopeCaptor =
                ArgumentCaptor.forClass(VerlaCommandEnvelope.class);
        verify(mqOutboxService).createVerlaCommand(envelopeCaptor.capture(),
                eq(COMMAND_EXCHANGE), eq(VerlaCommandAction.CMD_AITUTOR_CHAT.getCode()));
        VerlaCommandEnvelope envelope = envelopeCaptor.getValue();
        assertEquals(1, envelope.getSchemaVersion());
        assertEquals(VerlaCorrelationId.orderingKey(SESSION_ID), envelope.getOrderingKey());
        assertTrue(envelope.getMessageId().startsWith("cmd-"), envelope.getMessageId());
        assertEquals(VERLA_CONVERSATION_ID, envelope.getConversation().getConversationId());
        assertEquals("user_9001", envelope.getConversation().getUserId());
        assertEquals(TURN_ID, envelope.getTurn().getTurnId());
        assertEquals(SESSION_ID, envelope.getSession().getSessionId());
        assertEquals(VerlaSessionKind.AITUTOR, envelope.getSession().getKind());
        assertEquals(FeatureCode.DEMO_AI_TUTOR.getCode(), envelope.getSession().getFeature());
        assertTrue(VerlaCorrelationId.isValid(envelope.getCorrelationId()));

        Map<String, Object> payload = envelope.getPayload();
        assertEquals("帮我写第二章", payload.get("message"));
        assertEquals("示例论文", payload.get("paperTitle"));
        assertEquals("# 引言\n初稿", payload.get("documentContentMd"));
        assertEquals(2L, payload.get("documentBaseVersion"));
        assertEquals("chinese", payload.get("outputLanguage"));
        assertEquals(DEMO_CONVERSATION_ID, payload.get("demoConversationId"));
        // 刻意不复用 conversationId 这个名字，避免与主线 verla conversationId 混淆。
        assertTrue(!payload.containsKey("conversationId"));

        assertEquals(VERLA_CONVERSATION_ID, result.getVerlaConversationId());
        assertEquals(TURN_ID, result.getTurnId());
        assertEquals(SESSION_ID, result.getSessionId());
        assertEquals(session.getCorrelationId(), result.getCorrelationId());
    }

    @Test
    void dispatch_defaults_document_fields_on_the_first_turn() {
        when(conversationRepository.findById(VERLA_CONVERSATION_ID)).thenReturn(verlaConversation());
        when(conversationService.resolveOutputLanguage(any())).thenReturn("chinese");

        dispatcher.dispatch(demoConversation(), null, "先想个题目");

        ArgumentCaptor<VerlaCommandEnvelope> envelopeCaptor =
                ArgumentCaptor.forClass(VerlaCommandEnvelope.class);
        verify(mqOutboxService).createVerlaCommand(envelopeCaptor.capture(), any(), any());
        Map<String, Object> payload = envelopeCaptor.getValue().getPayload();
        assertEquals("", payload.get("documentContentMd"));
        assertEquals(0L, payload.get("documentBaseVersion"));
    }

    @Test
    void dispatch_rejects_demo_conversation_without_mainline_link() {
        AiTutorConversation unlinked = demoConversation();
        unlinked.setVerlaConversationId(null);

        assertThrows(BusinessException.class,
                () -> dispatcher.dispatch(unlinked, null, "你好"));

        // 一行都不该写：没有 verla conversation 就没有事件通道，派发出去必然丢。
        verifyNoInteractions(turnRepository, sessionRepository, mqOutboxService);
    }

    @Test
    void dispatch_rejects_dangling_mainline_conversation() {
        when(conversationRepository.findById(VERLA_CONVERSATION_ID)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> dispatcher.dispatch(demoConversation(), null, "你好"));

        verify(sessionRepository, never()).save(any());
        verifyNoInteractions(mqOutboxService);
    }

    private static AiTutorConversation demoConversation() {
        AiTutorConversation conversation = new AiTutorConversation();
        conversation.setId(DEMO_CONVERSATION_ID);
        conversation.setClerkUserId("user_1");
        conversation.setVerlaConversationId(VERLA_CONVERSATION_ID);
        conversation.setTitle("示例论文");
        conversation.setPaperMeta("{\"paperType\":\"thesis\"}");
        return conversation;
    }

    private static VerlaConversation verlaConversation() {
        return VerlaConversation.builder()
                .id(VERLA_CONVERSATION_ID)
                .userId("user_9001")
                .build();
    }
}
