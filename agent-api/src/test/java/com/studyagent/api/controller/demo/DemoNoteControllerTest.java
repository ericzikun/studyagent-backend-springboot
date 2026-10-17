package com.studyagent.api.controller.demo;

import com.studyagent.service.application.demo.DemoNoteService;
import com.studyagent.service.application.demo.NoteVerlaCommandDispatcher;
import com.studyagent.service.application.demo.dto.NoteGenerateDispatchResult;
import com.studyagent.service.application.demo.dto.NoteTurnContext;
import com.studyagent.service.application.demo.dto.NoteTurnPreparation;
import com.studyagent.service.domain.demo.note.NoteConversation;
import com.studyagent.service.domain.demo.note.NoteMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 笔记端点契约：路径、一回合入参透传、历史消息输出与 public id 输出。 */
class DemoNoteControllerTest {

    private static final long NOTE_CONVERSATION_ID = 44L;
    private static final long VERLA_CONVERSATION_ID = 11L;

    private DemoNoteService service;
    private NoteVerlaCommandDispatcher dispatcher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(DemoNoteService.class);
        dispatcher = mock(NoteVerlaCommandDispatcher.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DemoNoteController(service, dispatcher))
                .build();
    }

    @Test
    void draft_returns_conversation_with_mainline_public_id() throws Exception {
        when(service.getOrCreateDraft("user_1", "zh-CN")).thenReturn(conversation());

        mockMvc.perform(post("/v1/demo/note/conversations/draft")
                        .requestAttr("clerkUserId", "user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outputLanguage\":\"zh-CN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statusCode").value(0))
                .andExpect(jsonPath("$.data.id").value(NOTE_CONVERSATION_ID))
                .andExpect(jsonPath("$.data.verlaConversationId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("draft"));
    }

    @Test
    void list_returns_summaries_without_note_body() throws Exception {
        NoteConversation completed = conversation();
        completed.setStatus("completed");
        completed.setSourceType("file");
        // 历史栏不该拖正文：列表 VO 必须剔除 noteMd（几十条 × 数十 KB）
        completed.setNoteMd("# 很长的笔记正文");
        when(service.listProcessedConversations("user_1", 20)).thenReturn(List.of(completed));

        mockMvc.perform(get("/v1/demo/note/conversations")
                        .requestAttr("clerkUserId", "user_1")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(NOTE_CONVERSATION_ID))
                .andExpect(jsonPath("$.data[0].verlaConversationId").isNotEmpty())
                .andExpect(jsonPath("$.data[0].status").value("completed"))
                .andExpect(jsonPath("$.data[0].sourceType").value("file"))
                .andExpect(jsonPath("$.data[0].noteMd").doesNotExist());

        verify(service).listProcessedConversations("user_1", 20);
    }

    @Test
    void list_uses_default_limit_when_absent() throws Exception {
        when(service.listProcessedConversations("user_1", 50)).thenReturn(List.of());

        mockMvc.perform(get("/v1/demo/note/conversations")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        verify(service).listProcessedConversations("user_1", 50);
    }

    @Test
    void snapshot_resolves_public_id_from_path() throws Exception {
        when(service.resolveConversationId("user_1", "vc_abc")).thenReturn(NOTE_CONVERSATION_ID);
        when(service.getOwned("user_1", NOTE_CONVERSATION_ID)).thenReturn(conversation());

        mockMvc.perform(get("/v1/demo/note/conversations/vc_abc")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(NOTE_CONVERSATION_ID));
    }

    @Test
    void snapshot_returns_conversation_messages_for_the_chat_rail() throws Exception {
        when(service.resolveConversationId("user_1", "vc_abc")).thenReturn(NOTE_CONVERSATION_ID);
        when(service.getOwned("user_1", NOTE_CONVERSATION_ID)).thenReturn(conversation());
        when(service.listMessages(NOTE_CONVERSATION_ID)).thenReturn(List.of(
                message(1, "user", "帮我整理笔记"),
                message(2, "assistant", "好的，已生成。")));

        mockMvc.perform(get("/v1/demo/note/conversations/vc_abc")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].role").value("user"))
                .andExpect(jsonPath("$.data.messages[0].contentMd").value("帮我整理笔记"))
                .andExpect(jsonPath("$.data.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.data.messages[1].seq").value(2));
    }

    @Test
    void generate_prepares_first_turn_then_dispatches() throws Exception {
        NoteConversation prepared = conversation();
        when(service.prepareFirstTurn("user_1", NOTE_CONVERSATION_ID, "file", "obj_123", null, "lecture.pdf",
                "数据结构", "exam_prep", "zh-CN", "给我一份复习笔记"))
                .thenReturn(NoteTurnPreparation.builder().conversation(prepared).history(List.of()).build());
        when(dispatcher.dispatch(eq(prepared), any(NoteTurnContext.class)))
                .thenReturn(NoteGenerateDispatchResult.builder()
                        .verlaConversationId(VERLA_CONVERSATION_ID)
                        .turnId(22L)
                        .sessionId(33L)
                        .correlationId("conv:11:turn:22:sess:33")
                        .build());

        mockMvc.perform(post("/v1/demo/note/conversations/" + NOTE_CONVERSATION_ID + "/generate")
                        .requestAttr("clerkUserId", "user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"file\",\"objectId\":\"obj_123\","
                                + "\"filename\":\"lecture.pdf\",\"outputLanguage\":\"zh-CN\","
                                + "\"courseName\":\"数据结构\",\"learningGoal\":\"exam_prep\","
                                + "\"message\":\"给我一份复习笔记\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnId").isNotEmpty())
                .andExpect(jsonPath("$.data.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.data.correlationId").value("conv:11:turn:22:sess:33"));

        verify(service).prepareFirstTurn("user_1", NOTE_CONVERSATION_ID, "file", "obj_123", null, "lecture.pdf",
                "数据结构", "exam_prep", "zh-CN", "给我一份复习笔记");
        ArgumentCaptor<NoteTurnContext> ctx = ArgumentCaptor.forClass(NoteTurnContext.class);
        verify(dispatcher).dispatch(eq(prepared), ctx.capture());
        assertTrue(ctx.getValue().isFirstTurn());
        assertEquals("给我一份复习笔记", ctx.getValue().getMessage());
        assertEquals("", ctx.getValue().getDocumentContentMd());
        assertEquals(0L, ctx.getValue().getDocumentBaseVersion());
    }

    @Test
    void generate_follow_up_turn_passes_document_baseline_and_history() throws Exception {
        NoteConversation prepared = conversation();
        when(service.prepareFollowUpTurn("user_1", NOTE_CONVERSATION_ID, "把要点改简练点"))
                .thenReturn(NoteTurnPreparation.builder()
                        .conversation(prepared)
                        .history(List.of(Map.of("role", "user", "content", "旧问题")))
                        .build());
        when(dispatcher.dispatch(eq(prepared), any(NoteTurnContext.class)))
                .thenReturn(NoteGenerateDispatchResult.builder().build());

        mockMvc.perform(post("/v1/demo/note/conversations/" + NOTE_CONVERSATION_ID + "/generate")
                        .requestAttr("clerkUserId", "user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"把要点改简练点\","
                                + "\"documentContentMd\":\"# 主题\\n## 要点\\n- 长句\","
                                + "\"documentBaseVersion\":3}"))
                .andExpect(status().isOk());

        ArgumentCaptor<NoteTurnContext> ctx = ArgumentCaptor.forClass(NoteTurnContext.class);
        verify(dispatcher).dispatch(eq(prepared), ctx.capture());
        assertFalse(ctx.getValue().isFirstTurn());
        assertEquals("把要点改简练点", ctx.getValue().getMessage());
        assertEquals("# 主题\n## 要点\n- 长句", ctx.getValue().getDocumentContentMd());
        assertEquals(3L, ctx.getValue().getDocumentBaseVersion());
        assertEquals(1, ctx.getValue().getHistory().size());
    }

    @Test
    void save_note_patches_content_with_version_baseline() throws Exception {
        NoteConversation saved = conversation();
        saved.setNoteMd("手改后的正文");
        saved.setNoteVersion(3L);
        when(service.saveUserNote("user_1", NOTE_CONVERSATION_ID, "手改后的正文", 2L)).thenReturn(saved);

        mockMvc.perform(patch("/v1/demo/note/conversations/" + NOTE_CONVERSATION_ID)
                        .requestAttr("clerkUserId", "user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentMd\":\"手改后的正文\",\"baseVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noteMd").value("手改后的正文"))
                .andExpect(jsonPath("$.data.noteVersion").value(3));

        verify(service).saveUserNote("user_1", NOTE_CONVERSATION_ID, "手改后的正文", 2L);
    }

    @Test
    void snapshot_exposes_form_fields_and_note_version() throws Exception {
        NoteConversation conv = conversation();
        conv.setCourseName("数据结构");
        conv.setLearningGoal("exam_prep");
        conv.setNoteVersion(4L);
        when(service.resolveConversationId("user_1", "vc_abc")).thenReturn(NOTE_CONVERSATION_ID);
        when(service.getOwned("user_1", NOTE_CONVERSATION_ID)).thenReturn(conv);

        mockMvc.perform(get("/v1/demo/note/conversations/vc_abc")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.courseName").value("数据结构"))
                .andExpect(jsonPath("$.data.learningGoal").value("exam_prep"))
                .andExpect(jsonPath("$.data.noteVersion").value(4));
    }

    @Test
    void generate_treats_text_source_as_first_turn() throws Exception {
        NoteConversation prepared = conversation();
        when(service.prepareFirstTurn(eq("user_1"), eq(NOTE_CONVERSATION_ID), eq(null), eq(null),
                eq("一段材料"), eq(null), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(NoteTurnPreparation.builder().conversation(prepared).history(List.of()).build());
        when(dispatcher.dispatch(eq(prepared), any(NoteTurnContext.class)))
                .thenReturn(NoteGenerateDispatchResult.builder().build());

        mockMvc.perform(post("/v1/demo/note/conversations/" + NOTE_CONVERSATION_ID + "/generate")
                        .requestAttr("clerkUserId", "user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"一段材料\"}"))
                .andExpect(status().isOk());

        verify(service).prepareFirstTurn("user_1", NOTE_CONVERSATION_ID, null, null, "一段材料", null, null, null, null, null);
    }

    @Test
    void commit_proposal_passes_decisions_and_returns_the_updated_document() throws Exception {
        NoteConversation committed = conversation();
        committed.setNoteMd("# 主题\n\n## 要点\n- 新");
        committed.setNoteVersion(4L);
        when(service.commitProposal(eq("user_1"), eq(NOTE_CONVERSATION_ID), eq("np_1"), eq(3L), any()))
                .thenReturn(committed);

        mockMvc.perform(post("/v1/demo/note/conversations/" + NOTE_CONVERSATION_ID + "/proposal/commit")
                        .requestAttr("clerkUserId", "user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proposalId\":\"np_1\",\"baseVersion\":3,"
                                + "\"decisions\":[{\"hunkId\":\"h1\",\"status\":\"accepted\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noteMd").value("# 主题\n\n## 要点\n- 新"))
                .andExpect(jsonPath("$.data.noteVersion").value(4));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).commitProposal(eq("user_1"), eq(NOTE_CONVERSATION_ID), eq("np_1"), eq(3L), captor.capture());
        assertEquals("h1", captor.getValue().get(0).get("hunkId"));
        assertEquals("accepted", captor.getValue().get(0).get("status"));
    }

    @Test
    void snapshot_exposes_the_pending_proposal() throws Exception {
        NoteConversation conv = conversation();
        when(service.resolveConversationId("user_1", "vc_abc")).thenReturn(NOTE_CONVERSATION_ID);
        when(service.getOwned("user_1", NOTE_CONVERSATION_ID)).thenReturn(conv);
        when(service.parsePendingProposal(conv)).thenReturn(Map.of("proposalId", "np_1", "baseVersion", 3));

        // 刷新页面后仍能继续审阅：提案必须随快照带出来
        mockMvc.perform(get("/v1/demo/note/conversations/vc_abc")
                        .requestAttr("clerkUserId", "user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingProposal.proposalId").value("np_1"))
                .andExpect(jsonPath("$.data.pendingProposal.baseVersion").value(3));
    }

    private static NoteMessage message(int seq, String role, String content) {
        NoteMessage m = new NoteMessage();
        m.setSeq(seq);
        m.setRole(role);
        m.setMsgType(NoteMessage.TYPE_TEXT);
        m.setContentMd(content);
        return m;
    }

    private static NoteConversation conversation() {
        NoteConversation conversation = new NoteConversation();
        conversation.setId(NOTE_CONVERSATION_ID);
        conversation.setClerkUserId("user_1");
        conversation.setVerlaConversationId(VERLA_CONVERSATION_ID);
        conversation.setTitle("lecture.pdf");
        conversation.setStatus("draft");
        return conversation;
    }
}
