package com.studyagent.api.controller.demo;

import com.studyagent.service.application.demo.DemoNoteService;
import com.studyagent.service.application.demo.NoteVerlaCommandDispatcher;
import com.studyagent.service.application.demo.dto.NoteGenerateDispatchResult;
import com.studyagent.service.domain.demo.note.NoteConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 笔记端点契约：路径、入参透传与 public id 输出。 */
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
    void generate_persists_source_then_dispatches_command() throws Exception {
        NoteConversation prepared = conversation();
        when(service.prepareGeneration("user_1", NOTE_CONVERSATION_ID, "file", "obj_123", null, "lecture.pdf"))
                .thenReturn(prepared);
        when(dispatcher.dispatch(prepared, "zh-CN")).thenReturn(NoteGenerateDispatchResult.builder()
                .verlaConversationId(VERLA_CONVERSATION_ID)
                .turnId(22L)
                .sessionId(33L)
                .correlationId("conv:11:turn:22:sess:33")
                .build());

        mockMvc.perform(post("/v1/demo/note/conversations/" + NOTE_CONVERSATION_ID + "/generate")
                        .requestAttr("clerkUserId", "user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"file\",\"objectId\":\"obj_123\","
                                + "\"filename\":\"lecture.pdf\",\"outputLanguage\":\"zh-CN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnId").isNotEmpty())
                .andExpect(jsonPath("$.data.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.data.correlationId").value("conv:11:turn:22:sess:33"));

        verify(service).prepareGeneration("user_1", NOTE_CONVERSATION_ID, "file", "obj_123", null, "lecture.pdf");
        verify(dispatcher).dispatch(eq(prepared), eq("zh-CN"));
    }

    @Test
    void generate_defaults_missing_optionals_to_null() throws Exception {
        NoteConversation prepared = conversation();
        when(service.prepareGeneration(eq("user_1"), eq(NOTE_CONVERSATION_ID), isNull(), isNull(),
                eq("一段材料"), isNull())).thenReturn(prepared);
        when(dispatcher.dispatch(eq(prepared), isNull())).thenReturn(NoteGenerateDispatchResult.builder().build());

        mockMvc.perform(post("/v1/demo/note/conversations/" + NOTE_CONVERSATION_ID + "/generate")
                        .requestAttr("clerkUserId", "user_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"一段材料\"}"))
                .andExpect(status().isOk());

        verify(service).prepareGeneration("user_1", NOTE_CONVERSATION_ID, null, null, "一段材料", null);
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
