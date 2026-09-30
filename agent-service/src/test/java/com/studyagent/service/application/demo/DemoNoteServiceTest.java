package com.studyagent.service.application.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.domain.demo.note.NoteConversation;
import com.studyagent.service.domain.demo.note.repo.DemoNoteRepository;
import com.studyagent.service.domain.verla.VerlaConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 笔记会话编排的边界测试：素材类型推断、命令载荷体积护栏、草稿复用。 */
class DemoNoteServiceTest {

    private static final long VERLA_CONVERSATION_ID = 11L;
    private static final long NOTE_CONVERSATION_ID = 44L;

    private DemoNoteRepository repo;
    private VerlaConversationService verlaConversationService;
    private DemoNoteService service;

    @BeforeEach
    void setUp() {
        repo = mock(DemoNoteRepository.class);
        verlaConversationService = mock(VerlaConversationService.class);
        service = new DemoNoteService(repo, verlaConversationService, new ObjectMapper());
    }

    @Test
    void draft_creates_mainline_conversation_when_user_has_none() {
        when(repo.findLatestDraft("user_1")).thenReturn(Optional.empty());
        when(verlaConversationService.create(eq("user_1"), anyString(), anyString(), eq("NOTE_MAKER")))
                .thenReturn(VerlaConversation.builder().id(VERLA_CONVERSATION_ID).build());
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteConversation created = service.getOrCreateDraft("user_1", "zh-CN");

        assertEquals("draft", created.getStatus());
        assertEquals(VERLA_CONVERSATION_ID, created.getVerlaConversationId());
        verify(repo).saveConversation(any(NoteConversation.class));
    }

    @Test
    void draft_reuses_existing_draft_without_touching_mainline() {
        NoteConversation existing = new NoteConversation();
        existing.setId(NOTE_CONVERSATION_ID);
        existing.setStatus("draft");
        existing.setVerlaConversationId(VERLA_CONVERSATION_ID);
        when(repo.findLatestDraft("user_1")).thenReturn(Optional.of(existing));

        NoteConversation reused = service.getOrCreateDraft("user_1", "zh-CN");

        assertEquals(NOTE_CONVERSATION_ID, reused.getId());
        verify(verlaConversationService, never()).create(any(), any(), any(), any());
        verify(repo, never()).saveConversation(any(NoteConversation.class));
    }

    @Test
    void prepare_generation_infers_source_type_from_payload_shape() {
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(draft()));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteConversation fileNote = service.prepareGeneration(
                "user_1", NOTE_CONVERSATION_ID, null, "obj_9", null, "lecture.pdf");
        assertEquals("file", fileNote.getSourceType());
        assertEquals("obj_9", fileNote.getAttachmentObjectId());
        assertNull(fileNote.getInputText());
        assertEquals("generating", fileNote.getStatus());

        NoteConversation textNote = service.prepareGeneration(
                "user_1", NOTE_CONVERSATION_ID, null, null, "  一段材料  ", null);
        assertEquals("text", textNote.getSourceType());
        assertEquals("一段材料", textNote.getInputText());
        assertNull(textNote.getAttachmentObjectId());
    }

    @Test
    void prepare_generation_rejects_empty_input() {
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(draft()));

        assertThrows(BusinessException.class,
                () -> service.prepareGeneration("user_1", NOTE_CONVERSATION_ID, null, null, "   ", null));
        assertThrows(BusinessException.class,
                () -> service.prepareGeneration("user_1", NOTE_CONVERSATION_ID, "file", null, null, null));
        assertThrows(BusinessException.class,
                () -> service.prepareGeneration("user_1", NOTE_CONVERSATION_ID, "pdf", "obj", null, null));
    }

    @Test
    void prepare_generation_rejects_text_over_command_payload_budget() {
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(draft()));

        // mq_outbox.payload 仍是 TEXT（65535 字节）：超限必须在 API 边界拦下
        String tooLong = "中".repeat(20_001);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prepareGeneration("user_1", NOTE_CONVERSATION_ID, "text", null, tooLong, null));
        assertEquals(com.studyagent.common.api.ApiCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void resolve_conversation_id_rejects_non_public_numeric_identifier() {
        assertThrows(BusinessException.class, () -> service.resolveConversationId("user_1", ""));
        assertThrows(BusinessException.class, () -> service.resolveConversationId("user_1", "not-an-id"));
    }

    @Test
    void apply_generated_note_fills_note_and_marks_completed() {
        NoteConversation conversation = draft();
        conversation.setStatus("generating");
        when(repo.findByVerlaConversationId(VERLA_CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.applyGeneratedNote(VERLA_CONVERSATION_ID, "# 光合作用\n正文", "光合作用");

        assertEquals("# 光合作用\n正文", conversation.getNoteMd());
        assertEquals("光合作用", conversation.getTitle());
        assertEquals("completed", conversation.getStatus());
    }

    @Test
    void apply_generated_note_fails_loudly_when_unlinked() {
        when(repo.findByVerlaConversationId(VERLA_CONVERSATION_ID)).thenReturn(Optional.empty());

        // 脱链是配置错误，必须暴露而不是静默丢弃（否则前端看到笔记、库里没有）
        assertThrows(IllegalStateException.class,
                () -> service.applyGeneratedNote(VERLA_CONVERSATION_ID, "x", null));
    }

    @Test
    void list_conversations_clamps_limit_before_hitting_the_repository() {
        when(repo.listProcessedConversations(anyString(), anyInt())).thenReturn(List.of(draft()));

        service.listProcessedConversations("user_1", 0);
        service.listProcessedConversations("user_1", 5_000);
        service.listProcessedConversations("user_1", 30);

        // 上限 100 / 下限 1：避免调用方传 0 或超大值直接进 LIMIT
        verify(repo).listProcessedConversations("user_1", 1);
        verify(repo).listProcessedConversations("user_1", 100);
        verify(repo).listProcessedConversations("user_1", 30);
    }

    private static NoteConversation draft() {
        NoteConversation conversation = new NoteConversation();
        conversation.setId(NOTE_CONVERSATION_ID);
        conversation.setClerkUserId("user_1");
        conversation.setVerlaConversationId(VERLA_CONVERSATION_ID);
        conversation.setStatus("draft");
        return conversation;
    }
}
