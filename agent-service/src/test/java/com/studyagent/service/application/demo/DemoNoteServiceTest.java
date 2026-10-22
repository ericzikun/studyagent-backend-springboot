package com.studyagent.service.application.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.demo.dto.NoteTurnPreparation;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.domain.demo.note.NoteConversation;
import com.studyagent.service.domain.demo.note.NoteMessage;
import com.studyagent.service.domain.demo.note.repo.DemoNoteRepository;
import com.studyagent.service.domain.verla.VerlaConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 笔记会话编排的边界测试：素材类型推断、命令载荷体积护栏、草稿复用、回合消息与 history 裁剪。 */
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
        when(repo.appendMessage(any(NoteMessage.class))).thenAnswer(inv -> inv.getArgument(0));
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
    void first_turn_infers_source_type_and_records_query_as_first_message() {
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(draft()));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteTurnPreparation prepared = service.prepareFirstTurn(
                "user_1", NOTE_CONVERSATION_ID, null, "obj_9", null, "lecture.pdf",
                "  数据结构  ", "exam_prep", "zh-CN", "帮我整理一份复习笔记");

        NoteConversation fileNote = prepared.getConversation();
        assertEquals("file", fileNote.getSourceType());
        assertEquals("obj_9", fileNote.getAttachmentObjectId());
        // 文件名另存一份：title 会被 AI 起的会话名覆盖，左栏摘要气泡要靠它画文件 chip
        assertEquals("lecture.pdf", fileNote.getAttachmentFilename());
        assertEquals("zh-CN", fileNote.getOutputLanguage());
        assertNull(fileNote.getInputText());
        assertEquals("generating", fileNote.getStatus());
        assertEquals("数据结构", fileNote.getCourseName());
        assertEquals("exam_prep", fileNote.getLearningGoal());
        assertEquals(0L, fileNote.getNoteVersion());
        // 首轮 history 恒为空（本轮 query 单独走 message 字段）
        assertTrue(prepared.getHistory().isEmpty());
        // 重开一轮：先清空旧消息，再把 query 落成第 1 条用户消息
        verify(repo).deleteMessages(NOTE_CONVERSATION_ID);
        ArgumentCaptor<NoteMessage> msg = ArgumentCaptor.forClass(NoteMessage.class);
        verify(repo).appendMessage(msg.capture());
        assertEquals(NoteMessage.ROLE_USER, msg.getValue().getRole());
        assertEquals("帮我整理一份复习笔记", msg.getValue().getContentMd());
    }

    @Test
    void first_turn_syncs_the_filename_to_the_mainline_conversation_title() {
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(draft()));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.prepareFirstTurn("user_1", NOTE_CONVERSATION_ID, "file", "obj_9", null, "lecture.pdf",
                null, null, null, null);

        // Dashboard 的 AI Note 分区读的是主线 title（不是 demo 表），不同步就永远显示「未命名笔记」
        verify(verlaConversationService).rename("user_1", VERLA_CONVERSATION_ID, "lecture.pdf");
    }

    @Test
    void first_turn_without_filename_keeps_the_mainline_title_untouched() {
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(draft()));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        // 没有文件名 → demo title 仍是「未命名笔记」占位，没必要写主线
        service.prepareFirstTurn("user_1", NOTE_CONVERSATION_ID, "file", "obj_9", null, null,
                null, null, null, null);

        verify(verlaConversationService, never()).rename(any(), any(), any());
    }

    @Test
    void follow_up_turn_does_not_touch_the_title() {
        NoteConversation conversation = draft();
        conversation.setStatus("completed");
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.prepareFollowUpTurn("user_1", NOTE_CONVERSATION_ID, "再补充一点");

        verify(verlaConversationService, never()).rename(any(), any(), any());
    }

    @Test
    void first_turn_with_blank_query_records_no_message() {
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(draft()));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.prepareFirstTurn("user_1", NOTE_CONVERSATION_ID, "file", "obj_9", null, "a.pdf",
                null, null, null, "   ");

        // query 选填：为空时不该落空气泡
        verify(repo, never()).appendMessage(any(NoteMessage.class));
    }

    @Test
    void first_turn_normalizes_text_source_and_default_goal() {
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(draft()));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteConversation textNote = service.prepareFirstTurn(
                "user_1", NOTE_CONVERSATION_ID, null, null, "  一段材料  ", null, null, null, null, null)
                .getConversation();

        assertEquals("text", textNote.getSourceType());
        assertEquals("一段材料", textNote.getInputText());
        assertNull(textNote.getAttachmentObjectId());
        // 换回文本来源要清掉上一轮的文件名，否则摘要气泡会挂着一个不存在的文件
        assertNull(textNote.getAttachmentFilename());
        assertEquals("take_notes", textNote.getLearningGoal());
        assertNull(textNote.getCourseName());
    }

    @Test
    void first_turn_falls_back_on_unknown_learning_goal() {
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(draft()));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteConversation note = service.prepareFirstTurn(
                "user_1", NOTE_CONVERSATION_ID, "text", null, "材料", null, null, "not_a_goal", null, null)
                .getConversation();

        assertEquals("other", note.getLearningGoal());
    }

    @Test
    void resolve_referenced_notes_keeps_owned_notes_and_skips_self_and_others() {
        NoteConversation other = draft();
        other.setId(77L);
        other.setTitle("细胞呼吸");
        other.setNoteMd("糖酵解发生在细胞质基质。");
        when(repo.getOwnedConversation("user_1", 77L)).thenReturn(Optional.of(other));
        when(repo.getOwnedConversation("user_1", 88L)).thenReturn(Optional.empty());

        List<Map<String, Object>> notes = service.resolveReferencedNotes(
                "user_1", NOTE_CONVERSATION_ID, List.of(77L, 88L, 77L, NOTE_CONVERSATION_ID));

        // 别人的 id 静默跳过（不泄露存在性）、重复 id 只算一次、不能引用自己
        assertEquals(1, notes.size());
        assertEquals("细胞呼吸", notes.get(0).get("title"));
        assertEquals("糖酵解发生在细胞质基质。", notes.get(0).get("excerpt"));
    }

    @Test
    void resolve_referenced_notes_caps_count_and_truncates_excerpts() {
        for (long id = 100L; id < 110L; id += 1) {
            NoteConversation c = draft();
            c.setId(id);
            c.setTitle("笔记" + id);
            c.setNoteMd("字".repeat(5_000));
            when(repo.getOwnedConversation("user_1", id)).thenReturn(Optional.of(c));
        }

        List<Long> ids = new java.util.ArrayList<>();
        for (long id = 100L; id < 110L; id += 1) {
            ids.add(id);
        }
        List<Map<String, Object>> notes = service.resolveReferencedNotes("user_1", NOTE_CONVERSATION_ID, ids);

        // 两个预算同时生效：每条截 3000，总量封顶 8000 → 3000+3000+2000 三条
        assertEquals(3, notes.size());
        assertEquals(3_000, ((String) notes.get(0).get("excerpt")).length());
        assertEquals(Boolean.TRUE, notes.get(0).get("truncated"));
        int total = notes.stream().mapToInt(n -> ((String) n.get("excerpt")).length()).sum();
        assertEquals(8_000, total);
    }

    @Test
    void follow_up_turn_appends_user_message_and_builds_history_before_it() {
        NoteConversation conversation = draft();
        conversation.setStatus("completed");
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.listRecentMessages(NOTE_CONVERSATION_ID, 20))
                .thenReturn(List.of(message("user", "第一轮问题"), message("assistant", "第一轮回答")));

        NoteTurnPreparation prepared = service.prepareFollowUpTurn("user_1", NOTE_CONVERSATION_ID, "  再补充一点  ");

        assertEquals("generating", prepared.getConversation().getStatus());
        // history 必须是「本轮消息落库之前」的快照，否则会把当前这轮重复喂给模型
        assertEquals(2, prepared.getHistory().size());
        assertEquals("第一轮问题", prepared.getHistory().get(0).get("content"));
        ArgumentCaptor<NoteMessage> msg = ArgumentCaptor.forClass(NoteMessage.class);
        verify(repo).appendMessage(msg.capture());
        assertEquals("再补充一点", msg.getValue().getContentMd());
        // 后续轮不清空历史
        verify(repo, never()).deleteMessages(any());
    }

    @Test
    void follow_up_turn_rejects_blank_message() {
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(draft()));

        assertThrows(BusinessException.class,
                () -> service.prepareFollowUpTurn("user_1", NOTE_CONVERSATION_ID, "   "));
        verify(repo, never()).appendMessage(any(NoteMessage.class));
    }

    @Test
    void build_history_applies_char_budget_from_the_newest_backwards() {
        when(repo.listRecentMessages(NOTE_CONVERSATION_ID, 20)).thenReturn(List.of(
                message("user", "中".repeat(8_000)),
                message("assistant", "中".repeat(8_000)),
                message("user", "中".repeat(8_000))));

        List<Map<String, Object>> history = service.buildHistory(NOTE_CONVERSATION_ID);

        // 预算 12_000：只能装下最近一条 8_000 字（再加一条就超），且必须是连续尾段
        assertEquals(1, history.size());
        assertEquals("user", history.get(0).get("role"));
        assertEquals(8_000, ((String) history.get(0).get("content")).length());
    }

    @Test
    void build_history_skips_blank_content_and_normalizes_role() {
        when(repo.listRecentMessages(NOTE_CONVERSATION_ID, 20)).thenReturn(List.of(
                message("system", "忽略我"),
                message("assistant", "   "),
                message("assistant", "有效回答")));

        List<Map<String, Object>> history = service.buildHistory(NOTE_CONVERSATION_ID);

        assertEquals(2, history.size());
        // 未知 role 归一到 user，避免把脏 role 灌进模型
        assertEquals("user", history.get(0).get("role"));
        assertEquals("有效回答", history.get(1).get("content"));
    }

    @Test
    void save_user_note_bumps_version_and_rejects_stale_base() {
        NoteConversation conversation = draft();
        conversation.setStatus("completed");
        conversation.setNoteMd("旧正文");
        conversation.setNoteVersion(2L);
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteConversation saved = service.saveUserNote("user_1", NOTE_CONVERSATION_ID, "手改后的正文", 2L);
        assertEquals("手改后的正文", saved.getNoteMd());
        assertEquals(3L, saved.getNoteVersion());

        conversation.setNoteVersion(5L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveUserNote("user_1", NOTE_CONVERSATION_ID, "又手改", 3L));
        assertEquals(com.studyagent.common.api.ApiCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void artifact_commit_advances_version_from_db_baseline_not_from_ai_echo() {
        NoteConversation conversation = draft();
        conversation.setNoteVersion(1L);
        when(repo.findByVerlaConversationId(VERLA_CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        // 用户手改过一版（库为 1），AI 回显自己的号（例如 2）：以库为准 → 2
        service.applyArtifactCommit(VERLA_CONVERSATION_ID, "# 新生成的笔记", 2L);

        assertEquals("# 新生成的笔记", conversation.getNoteMd());
        assertEquals(2L, conversation.getNoteVersion());
        assertEquals("completed", conversation.getStatus());
    }

    @Test
    void artifact_commit_rejects_empty_content_and_unlinked_conversation() {
        when(repo.findByVerlaConversationId(VERLA_CONVERSATION_ID)).thenReturn(Optional.of(draft()));
        assertThrows(IllegalStateException.class,
                () -> service.applyArtifactCommit(VERLA_CONVERSATION_ID, "   ", 1L));

        when(repo.findByVerlaConversationId(VERLA_CONVERSATION_ID)).thenReturn(Optional.empty());
        // 脱链是配置错误，必须暴露而不是静默丢弃
        assertThrows(IllegalStateException.class,
                () -> service.applyArtifactCommit(VERLA_CONVERSATION_ID, "x", 1L));
    }

    @Test
    void complete_turn_appends_assistant_message_and_marks_completed() {
        NoteConversation conversation = draft();
        conversation.setStatus("generating");
        when(repo.findByVerlaConversationId(VERLA_CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.completeTurn(VERLA_CONVERSATION_ID, "  这是回答  ");

        assertEquals("completed", conversation.getStatus());
        ArgumentCaptor<NoteMessage> msg = ArgumentCaptor.forClass(NoteMessage.class);
        verify(repo).appendMessage(msg.capture());
        assertEquals(NoteMessage.ROLE_ASSISTANT, msg.getValue().getRole());
        assertEquals("这是回答", msg.getValue().getContentMd());
    }

    @Test
    void complete_turn_skips_blank_assistant_text() {
        NoteConversation conversation = draft();
        conversation.setStatus("generating");
        when(repo.findByVerlaConversationId(VERLA_CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.completeTurn(VERLA_CONVERSATION_ID, "   ");

        // 弱模型「只调工具不说话」：不能落空气泡
        verify(repo, never()).appendMessage(any(NoteMessage.class));
        assertEquals("completed", conversation.getStatus());
    }

    @Test
    void is_first_turn_input_matches_python_side_predicate() {
        assertTrue(DemoNoteService.isFirstTurnInput(null, "obj_9", null));
        assertTrue(DemoNoteService.isFirstTurnInput("text", null, "素材"));
        assertTrue(DemoNoteService.isFirstTurnInput("file", "obj_9", null));
        assertFalse(DemoNoteService.isFirstTurnInput(null, null, null));
        assertFalse(DemoNoteService.isFirstTurnInput(null, "  ", "  "));
    }

    @Test
    void prepare_first_turn_rejects_empty_input() {
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(draft()));

        assertThrows(BusinessException.class, () -> service.prepareFirstTurn(
                "user_1", NOTE_CONVERSATION_ID, null, null, "   ", null, null, null, null, null));
        assertThrows(BusinessException.class, () -> service.prepareFirstTurn(
                "user_1", NOTE_CONVERSATION_ID, "file", null, null, null, null, null, null, null));
        assertThrows(BusinessException.class, () -> service.prepareFirstTurn(
                "user_1", NOTE_CONVERSATION_ID, "pdf", "obj", null, null, null, null, null, null));
    }

    @Test
    void prepare_first_turn_rejects_text_over_command_payload_budget() {
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(draft()));

        String tooLong = "中".repeat(20_001);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.prepareFirstTurn(
                "user_1", NOTE_CONVERSATION_ID, "text", null, tooLong, null, null, null, null, null));
        assertEquals(com.studyagent.common.api.ApiCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    // ---------- 章节级改动提案（后续轮） ----------

    private static Map<String, Object> hunk(String id, String original, String proposed,
                                             String anchorOriginal, String prefix, String suffix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("kind", "replace");
        body.put("originalText", original);
        body.put("proposedText", proposed);
        body.put("anchor", Map.of(
                "from", 5, "to", 12, "originalText", anchorOriginal,
                "prefix", prefix, "suffix", suffix));
        return body;
    }

    private static Map<String, Object> oneHunkProposal(String proposalId, long baseVersion, String proposedMd) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposalId", proposalId);
        body.put("baseVersion", baseVersion);
        body.put("proposedContentMd", proposedMd);
        body.put("changes", List.of(hunk(
                "h1", "## 要点\n- 旧", "## 要点\n- 新",
                "## 要点\n- 旧", "# 主题\n\n", "\n\n## 小结")));
        return body;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void pending_proposal_is_stored_without_touching_content_or_version() {
        NoteConversation conversation = draft();
        conversation.setStatus("completed");
        conversation.setNoteMd("# 主题\n\n## 要点\n- 旧\n\n## 小结");
        conversation.setNoteVersion(3L);
        when(repo.findByVerlaConversationId(VERLA_CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.savePendingProposal(VERLA_CONVERSATION_ID,
                oneHunkProposal("np_1", 3L, "# 主题\n\n## 要点\n- 新\n\n## 小结"));

        // 提案只挂在待审列上：正文与版本必须原封不动
        assertEquals("# 主题\n\n## 要点\n- 旧\n\n## 小结", conversation.getNoteMd());
        assertEquals(3L, conversation.getNoteVersion());
        assertTrue(conversation.getPendingProposalJson().contains("np_1"));
        assertEquals("np_1", service.parsePendingProposal(conversation).get("proposalId"));
    }

    @Test
    void stale_pending_proposal_replay_is_dropped() {
        // 事件重放会把已提交过的提案再写回来：baseVersion 落后于当前版本时必须丢弃，
        // 否则前端下次快照又进审阅态、点接受永远版本冲突（线上 vc_Ro5jW）。
        NoteConversation conversation = draft();
        conversation.setStatus("completed");
        conversation.setNoteVersion(3L);
        when(repo.findByVerlaConversationId(VERLA_CONVERSATION_ID)).thenReturn(Optional.of(conversation));

        service.savePendingProposal(VERLA_CONVERSATION_ID,
                oneHunkProposal("np_old", 2L, "# 主题\n\n## 要点\n- 新"));

        assertNull(conversation.getPendingProposalJson());
        verify(repo, never()).saveConversation(any(NoteConversation.class));
    }

    @Test
    void commit_proposal_accepting_all_uses_the_proposed_md_and_bumps_version_once() {
        NoteConversation conversation = draft();
        conversation.setStatus("completed");
        conversation.setNoteMd("# 主题\n\n## 要点\n- 旧\n\n## 小结");
        conversation.setNoteVersion(3L);
        conversation.setPendingProposalJson(
                toJson(oneHunkProposal("np_1", 3L, "# 主题\n\n## 要点\n- 新\n\n## 小结")));
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteConversation result = service.commitProposal("user_1", NOTE_CONVERSATION_ID, "np_1", 3L,
                List.of(Map.of("hunkId", "h1", "status", "accepted")));

        assertEquals("# 主题\n\n## 要点\n- 新\n\n## 小结", result.getNoteMd());
        assertEquals(4L, result.getNoteVersion());
        assertNull(result.getPendingProposalJson());
    }

    @Test
    void commit_proposal_rejecting_everything_leaves_content_and_version_alone() {
        NoteConversation conversation = draft();
        conversation.setNoteMd("# 主题\n\n## 要点\n- 旧\n\n## 小结");
        conversation.setNoteVersion(3L);
        conversation.setPendingProposalJson(
                toJson(oneHunkProposal("np_1", 3L, "# 主题\n\n## 要点\n- 新\n\n## 小结")));
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteConversation result = service.commitProposal("user_1", NOTE_CONVERSATION_ID, "np_1", 3L,
                List.of(Map.of("hunkId", "h1", "status", "rejected")));

        // 「拒绝则不改变」：正文与版本都不动，只把待审清掉
        assertEquals("# 主题\n\n## 要点\n- 旧\n\n## 小结", result.getNoteMd());
        assertEquals(3L, result.getNoteVersion());
        assertNull(result.getPendingProposalJson());
    }

    @Test
    void commit_proposal_applies_only_the_accepted_hunks() {
        NoteConversation conversation = draft();
        conversation.setNoteMd("# 主题\n\n## A\n- 旧A\n\n## B\n- 旧B");
        conversation.setNoteVersion(1L);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposalId", "np_2");
        body.put("baseVersion", 1);
        body.put("proposedContentMd", "# 主题\n\n## A\n- 新A\n\n## B\n- 新B");
        body.put("changes", List.of(
                hunk("h1", "## A\n- 旧A", "## A\n- 新A", "## A\n- 旧A", "", "\n\n## B"),
                hunk("h2", "## B\n- 旧B", "## B\n- 新B", "## B\n- 旧B", "\n\n", "")));
        conversation.setPendingProposalJson(toJson(body));
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        NoteConversation result = service.commitProposal("user_1", NOTE_CONVERSATION_ID, "np_2", 1L, List.of(
                Map.of("hunkId", "h1", "status", "accepted"),
                Map.of("hunkId", "h2", "status", "rejected")));

        // 部分接受：只应用 A，B 保持原样（不能拿 proposedContentMd 整篇覆盖）
        assertTrue(result.getNoteMd().contains("- 新A"));
        assertTrue(result.getNoteMd().contains("- 旧B"));
        assertEquals(2L, result.getNoteVersion());
    }

    @Test
    void commit_proposal_rejects_a_stale_base_version() {
        NoteConversation conversation = draft();
        conversation.setNoteVersion(5L);
        conversation.setPendingProposalJson(toJson(oneHunkProposal("np_1", 3L, "x")));
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(conversation));

        // 审阅期间基线又动过 → 必须让前端刷新重审
        BusinessException ex = assertThrows(BusinessException.class, () -> service.commitProposal(
                "user_1", NOTE_CONVERSATION_ID, "np_1", 3L,
                List.of(Map.of("hunkId", "h1", "status", "accepted"))));
        assertEquals(com.studyagent.common.api.ApiCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void commit_proposal_rejects_a_superseded_proposal_id() {
        NoteConversation conversation = draft();
        conversation.setNoteVersion(3L);
        conversation.setPendingProposalJson(toJson(oneHunkProposal("np_new", 3L, "x")));
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(conversation));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.commitProposal(
                "user_1", NOTE_CONVERSATION_ID, "np_old", 3L, List.of()));
        assertEquals(com.studyagent.common.api.ApiCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void commit_proposal_fails_loudly_when_an_accepted_hunk_cannot_be_located() {
        NoteConversation conversation = draft();
        // 用户手改过：h1 那段原文已不在正文里（h2 仍在，用来走「部分接受」这条路径 ——
        // 全接受时直接用 proposedContentMd，不逐块定位）
        conversation.setNoteMd("# 主题\n\n## A\n- 完全不同的内容\n\n## B\n- 旧B");
        conversation.setNoteVersion(3L);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposalId", "np_3");
        body.put("baseVersion", 3);
        body.put("proposedContentMd", "# 主题\n\n## A\n- 新A\n\n## B\n- 新B");
        body.put("changes", List.of(
                hunk("h1", "## A\n- 旧A", "## A\n- 新A", "## A\n- 旧A", "", "\n\n## B"),
                hunk("h2", "## B\n- 旧B", "## B\n- 新B", "## B\n- 旧B", "\n\n", "")));
        conversation.setPendingProposalJson(toJson(body));
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(conversation));

        // 定位不到 → 报错让前端刷新，绝不静默跳过
        BusinessException ex = assertThrows(BusinessException.class, () -> service.commitProposal(
                "user_1", NOTE_CONVERSATION_ID, "np_3", 3L, List.of(
                        Map.of("hunkId", "h1", "status", "accepted"),
                        Map.of("hunkId", "h2", "status", "rejected"))));
        assertEquals(com.studyagent.common.api.ApiCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void commit_proposal_without_any_pending_proposal_is_rejected() {
        NoteConversation conversation = draft();
        when(repo.getOwnedConversation("user_1", NOTE_CONVERSATION_ID)).thenReturn(Optional.of(conversation));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.commitProposal(
                "user_1", NOTE_CONVERSATION_ID, "np_1", 0L, List.of()));
        assertEquals(com.studyagent.common.api.ApiCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void generated_title_writes_both_tables() {
        NoteConversation conversation = draft();
        conversation.setTitle("Biz Ethics SP 2025.pdf");
        when(repo.findByVerlaConversationId(VERLA_CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(repo.saveConversation(any(NoteConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.applyGeneratedTitle(VERLA_CONVERSATION_ID, "  Business Ethics Memo Guidelines  ");

        assertEquals("Business Ethics Memo Guidelines", conversation.getTitle());
        // 工作台读 demo 表、Dashboard 读主线表：两处都必须写
        verify(verlaConversationService).rename(
                "user_1", VERLA_CONVERSATION_ID, "Business Ethics Memo Guidelines");
    }

    @Test
    void generated_title_ignores_blank_and_keeps_status_version_untouched() {
        NoteConversation conversation = draft();
        conversation.setStatus("generating");
        conversation.setNoteVersion(3L);
        when(repo.findByVerlaConversationId(VERLA_CONVERSATION_ID)).thenReturn(Optional.of(conversation));

        service.applyGeneratedTitle(VERLA_CONVERSATION_ID, "   ");

        verify(repo, never()).saveConversation(any(NoteConversation.class));
        verify(verlaConversationService, never()).rename(any(), any(), any());
        assertEquals("generating", conversation.getStatus());
        assertEquals(3L, conversation.getNoteVersion());
    }

    @Test
    void generated_title_for_an_unlinked_conversation_only_warns() {
        when(repo.findByVerlaConversationId(VERLA_CONVERSATION_ID)).thenReturn(Optional.empty());

        // 标题丢失只是不好看：绝不能抛异常（handler 抛异常会让该事件标 FAILED、cursor 不推进，
        // 用「好不好看」的失败把整条会话卡住）
        service.applyGeneratedTitle(VERLA_CONVERSATION_ID, "Some Title");

        verify(repo, never()).saveConversation(any(NoteConversation.class));
    }

    @Test
    void resolve_conversation_id_rejects_non_public_numeric_identifier() {
        assertThrows(BusinessException.class, () -> service.resolveConversationId("user_1", ""));
        assertThrows(BusinessException.class, () -> service.resolveConversationId("user_1", "not-an-id"));
    }

    @Test
    void list_conversations_clamps_limit_before_hitting_the_repository() {
        when(repo.listProcessedConversations(anyString(), anyInt())).thenReturn(List.of(draft()));

        service.listProcessedConversations("user_1", 0);
        service.listProcessedConversations("user_1", 5_000);
        service.listProcessedConversations("user_1", 30);

        verify(repo).listProcessedConversations("user_1", 1);
        verify(repo).listProcessedConversations("user_1", 100);
        verify(repo).listProcessedConversations("user_1", 30);
    }

    @Test
    void list_messages_is_old_to_new_from_the_repository() {
        List<NoteMessage> ordered = new ArrayList<>(List.of(message("user", "问"), message("assistant", "答")));
        when(repo.listRecentMessages(NOTE_CONVERSATION_ID, 200)).thenReturn(ordered);

        List<NoteMessage> messages = service.listMessages(NOTE_CONVERSATION_ID);

        assertEquals(List.of("问", "答"), messages.stream().map(NoteMessage::getContentMd).toList());
    }

    private static NoteMessage message(String role, String content) {
        NoteMessage m = new NoteMessage();
        m.setRole(role);
        m.setMsgType(NoteMessage.TYPE_TEXT);
        m.setContentMd(content);
        return m;
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
