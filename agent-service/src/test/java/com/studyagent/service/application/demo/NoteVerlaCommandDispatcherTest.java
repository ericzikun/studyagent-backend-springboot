package com.studyagent.service.application.demo;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.common.verla.enums.VerlaSessionKind;
import com.studyagent.common.verla.util.VerlaCorrelationId;
import com.studyagent.service.application.MqOutboxService;
import com.studyagent.service.application.demo.dto.NoteGenerateDispatchResult;
import com.studyagent.service.application.demo.dto.NoteTurnContext;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.domain.demo.note.NoteConversation;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * 笔记派发器的契约测试。
 * <p>重点是把 demo 里出过事的三条约定钉住：
 * <ol>
 *   <li>correlationId 必须先 save 拿自增 id 再回填（uk_correlation 唯一约束）；</li>
 *   <li>命令必须走 {@code MqOutboxService}（事务性 outbox），不能裸发；</li>
 *   <li>payload 的 key 是 Java → Python 的隐式契约，Python 侧按 key 直接取值。</li>
 * </ol>
 * <p>另有一条本轮新增、最容易踩的：<b>素材字段只在首轮下发</b> —— Python 以
 * 「是否带 objectId/inputText」判定首轮，后续轮若也带上会被重复解析。
 */
class NoteVerlaCommandDispatcherTest {

    private static final long VERLA_CONVERSATION_ID = 11L;
    private static final long TURN_ID = 22L;
    private static final long SESSION_ID = 33L;
    private static final long NOTE_CONVERSATION_ID = 44L;
    private static final String COMMAND_EXCHANGE = "studyagent.command";

    private VerlaConversationRepository conversationRepository;
    private VerlaTurnRepository turnRepository;
    private VerlaSessionRepository sessionRepository;
    private VerlaConversationService conversationService;
    private MqOutboxService mqOutboxService;
    private NoteVerlaCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(VerlaConversationRepository.class);
        turnRepository = mock(VerlaTurnRepository.class);
        sessionRepository = mock(VerlaSessionRepository.class);
        conversationService = mock(VerlaConversationService.class);
        mqOutboxService = mock(MqOutboxService.class);

        dispatcher = new NoteVerlaCommandDispatcher(
                conversationRepository,
                turnRepository,
                sessionRepository,
                new SessionStateMachine(),
                conversationService,
                mqOutboxService);
        ReflectionTestUtils.setField(dispatcher, "commandExchange", COMMAND_EXCHANGE);

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
    void dispatch_creates_mainline_rows_and_enqueues_transactional_outbox_for_first_turn() {
        when(conversationRepository.findById(VERLA_CONVERSATION_ID)).thenReturn(verlaConversation());
        when(conversationService.resolveOutputLanguage(any())).thenReturn("chinese");

        NoteGenerateDispatchResult result = dispatcher.dispatch(fileConversation(), firstTurn(null));

        ArgumentCaptor<VerlaTurn> turnCaptor = ArgumentCaptor.forClass(VerlaTurn.class);
        verify(turnRepository).save(turnCaptor.capture());
        assertEquals(VERLA_CONVERSATION_ID, turnCaptor.getValue().getConversationId());
        verify(conversationRepository).touchOnNewTurn(VERLA_CONVERSATION_ID, TURN_ID);

        ArgumentCaptor<VerlaSession> sessionCaptor = ArgumentCaptor.forClass(VerlaSession.class);
        verify(sessionRepository, org.mockito.Mockito.times(2)).save(sessionCaptor.capture());
        VerlaSession session = sessionCaptor.getValue();
        assertEquals(VerlaSessionKind.NOTE.name(), session.getKind());
        assertEquals(FeatureCode.DEMO_NOTE_MAKER.getCode(), session.getFeatureCode());
        assertEquals(SessionStatus.DISPATCHING.name(), session.getStatus());
        assertNotEquals("placeholder", session.getCorrelationId());
        assertEquals(VerlaCorrelationId.of(VERLA_CONVERSATION_ID, TURN_ID, SESSION_ID),
                session.getCorrelationId());

        ArgumentCaptor<VerlaCommandEnvelope> envelopeCaptor =
                ArgumentCaptor.forClass(VerlaCommandEnvelope.class);
        verify(mqOutboxService).createVerlaCommand(envelopeCaptor.capture(),
                eq(COMMAND_EXCHANGE), eq(VerlaCommandAction.CMD_NOTE_GENERATE.getCode()));
        VerlaCommandEnvelope envelope = envelopeCaptor.getValue();
        assertEquals(1, envelope.getSchemaVersion());
        assertEquals(VerlaCorrelationId.orderingKey(SESSION_ID), envelope.getOrderingKey());
        assertTrue(envelope.getMessageId().startsWith("cmd-"), envelope.getMessageId());
        assertEquals(VERLA_CONVERSATION_ID, envelope.getConversation().getConversationId());
        assertEquals("user_9001", envelope.getConversation().getUserId());
        assertEquals(TURN_ID, envelope.getTurn().getTurnId());
        assertEquals(SESSION_ID, envelope.getSession().getSessionId());
        assertEquals(VerlaSessionKind.NOTE, envelope.getSession().getKind());
        assertEquals(FeatureCode.DEMO_NOTE_MAKER.getCode(), envelope.getSession().getFeature());
        assertTrue(VerlaCorrelationId.isValid(envelope.getCorrelationId()));

        Map<String, Object> payload = envelope.getPayload();
        // 首轮带素材
        assertEquals("file", payload.get("sourceType"));
        assertEquals("obj_123", payload.get("objectId"));
        assertNull(payload.get("inputText"));
        assertEquals("lecture.pdf", payload.get("filename"));
        // 回合通用字段
        assertEquals("chinese", payload.get("outputLanguage"));
        assertEquals("数据结构", payload.get("courseName"));
        assertEquals("exam_prep", payload.get("learningGoal"));
        assertEquals("给我一份复习笔记", payload.get("message"));
        assertEquals("", payload.get("documentContentMd"));
        assertEquals(0L, payload.get("documentBaseVersion"));
        assertEquals(List.of(), payload.get("history"));
        assertEquals(NOTE_CONVERSATION_ID, payload.get("noteConversationId"));
        assertFalse(payload.containsKey("conversationId"));

        assertEquals(VERLA_CONVERSATION_ID, result.getVerlaConversationId());
        assertEquals(TURN_ID, result.getTurnId());
        assertEquals(SESSION_ID, result.getSessionId());
        assertEquals(session.getCorrelationId(), result.getCorrelationId());
    }

    @Test
    void dispatch_omits_source_fields_on_follow_up_turn() {
        when(conversationRepository.findById(VERLA_CONVERSATION_ID)).thenReturn(verlaConversation());
        when(conversationService.resolveOutputLanguage(any())).thenReturn("chinese");

        NoteTurnContext followUp = NoteTurnContext.builder()
                .firstTurn(false)
                .message("把要点那节改简练点")
                .documentContentMd("# 主题\n## 要点\n- 长句")
                .documentBaseVersion(3L)
                .history(List.of(Map.of("role", "user", "content", "旧问题")))
                .build();

        // 会话行上仍留着首轮的素材，但后续轮绝不能下发（否则 Python 会重复解析）
        dispatcher.dispatch(fileConversation(), followUp);

        ArgumentCaptor<VerlaCommandEnvelope> envelopeCaptor =
                ArgumentCaptor.forClass(VerlaCommandEnvelope.class);
        verify(mqOutboxService).createVerlaCommand(envelopeCaptor.capture(), any(), any());
        Map<String, Object> payload = envelopeCaptor.getValue().getPayload();
        assertFalse(payload.containsKey("sourceType"));
        assertFalse(payload.containsKey("objectId"));
        assertFalse(payload.containsKey("inputText"));
        assertFalse(payload.containsKey("filename"));
        // 后续轮该有的都在
        assertEquals("把要点那节改简练点", payload.get("message"));
        assertEquals("# 主题\n## 要点\n- 长句", payload.get("documentContentMd"));
        assertEquals(3L, payload.get("documentBaseVersion"));
        assertEquals(1, ((List<?>) payload.get("history")).size());
    }

    @Test
    void dispatch_keeps_text_source_free_of_filename_echo() {
        when(conversationRepository.findById(VERLA_CONVERSATION_ID)).thenReturn(verlaConversation());
        when(conversationService.resolveOutputLanguage(any())).thenReturn("chinese");

        NoteConversation textConversation = fileConversation();
        textConversation.setSourceType("text");
        textConversation.setAttachmentObjectId(null);
        textConversation.setInputText("一段原始材料");
        // 文本来源的 title 是正文片段，不能被当作来源文件名回传
        textConversation.setTitle("一段原始材料");

        dispatcher.dispatch(textConversation, firstTurn(null));

        ArgumentCaptor<VerlaCommandEnvelope> envelopeCaptor =
                ArgumentCaptor.forClass(VerlaCommandEnvelope.class);
        verify(mqOutboxService).createVerlaCommand(envelopeCaptor.capture(), any(), any());
        Map<String, Object> payload = envelopeCaptor.getValue().getPayload();
        assertEquals("text", payload.get("sourceType"));
        assertEquals("一段原始材料", payload.get("inputText"));
        assertNull(payload.get("objectId"));
        assertNull(payload.get("filename"));
    }

    @Test
    void dispatch_overrides_output_language_when_request_carries_one() {
        VerlaConversation verlaConversation = verlaConversation();
        when(conversationRepository.findById(VERLA_CONVERSATION_ID)).thenReturn(verlaConversation);
        when(conversationService.setOutputLanguage(any(), eq("ja"))).thenReturn(verlaConversation);
        when(conversationService.resolveOutputLanguage(any())).thenReturn("japanese");

        dispatcher.dispatch(fileConversation(), firstTurn("ja"));

        verify(conversationService).setOutputLanguage(verlaConversation, "ja");
        ArgumentCaptor<VerlaCommandEnvelope> envelopeCaptor =
                ArgumentCaptor.forClass(VerlaCommandEnvelope.class);
        verify(mqOutboxService).createVerlaCommand(envelopeCaptor.capture(), any(), any());
        assertEquals("japanese", envelopeCaptor.getValue().getPayload().get("outputLanguage"));
    }

    @Test
    void dispatch_rejects_conversation_without_mainline_link() {
        NoteConversation unlinked = fileConversation();
        unlinked.setVerlaConversationId(null);

        assertThrows(BusinessException.class, () -> dispatcher.dispatch(unlinked, firstTurn(null)));

        // 没有 verla conversation 就没有事件通道，一行都不该写
        verifyNoInteractions(turnRepository, sessionRepository, mqOutboxService);
    }

    @Test
    void dispatch_rejects_dangling_mainline_conversation() {
        when(conversationRepository.findById(VERLA_CONVERSATION_ID)).thenReturn(null);

        assertThrows(BusinessException.class, () -> dispatcher.dispatch(fileConversation(), firstTurn(null)));

        verify(sessionRepository, never()).save(any());
        verifyNoInteractions(mqOutboxService);
    }

    private static NoteTurnContext firstTurn(String outputLanguage) {
        return NoteTurnContext.builder()
                .firstTurn(true)
                .message("给我一份复习笔记")
                .documentContentMd("")
                .documentBaseVersion(0L)
                .history(List.of())
                .outputLanguage(outputLanguage)
                .build();
    }

    private static NoteConversation fileConversation() {
        NoteConversation conversation = new NoteConversation();
        conversation.setId(NOTE_CONVERSATION_ID);
        conversation.setClerkUserId("user_1");
        conversation.setVerlaConversationId(VERLA_CONVERSATION_ID);
        conversation.setSourceType("file");
        conversation.setAttachmentObjectId("obj_123");
        conversation.setTitle("lecture.pdf");
        conversation.setCourseName("数据结构");
        conversation.setLearningGoal("exam_prep");
        conversation.setStatus("generating");
        return conversation;
    }

    private static VerlaConversation verlaConversation() {
        return VerlaConversation.builder()
                .id(VERLA_CONVERSATION_ID)
                .userId("user_9001")
                .build();
    }
}
