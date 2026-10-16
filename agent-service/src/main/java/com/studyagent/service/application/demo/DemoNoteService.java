package com.studyagent.service.application.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.enums.OutputLanguage;
import com.studyagent.common.verla.id.VerlaPublicId;
import com.studyagent.common.verla.id.VerlaPublicIdCodec;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import com.studyagent.service.application.demo.dto.NoteTurnPreparation;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.domain.demo.note.NoteConversation;
import com.studyagent.service.domain.demo.note.NoteMessage;
import com.studyagent.service.domain.demo.note.repo.DemoNoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 笔记 demo 业务服务：会话编排（素材提交 / 后续对话 / 结果回填）。
 * <p>解析、归纳与「是否要改文档」的判断都在 verla_agent（MQ 命令），事件回流走主线
 * Verla 通道；本服务只维护 demo 自己的会话行、回合消息与产物。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoNoteService {

    /**
     * 主线 verla_conversations.primary_intent 取值。
     * <p>必须非空且不属于任何已知分栏，否则 VerlaConversationMapper 会把
     * {@code primary_intent IS NULL OR = ''} 的会话归进 Dashboard 的作业分栏。
     */
    private static final String PRIMARY_INTENT_NOTE = "NOTE_MAKER";

    public static final String SOURCE_TYPE_FILE = "file";
    public static final String SOURCE_TYPE_TEXT = "text";

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_GENERATING = "generating";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    private static final String DRAFT_TITLE = "未命名笔记";
    private static final int TITLE_MAX_LENGTH = 60;
    private static final int COURSE_NAME_MAX_LENGTH = 255;
    /** 历史列表单页上限（单用户记录量小，暂不做游标分页）。 */
    private static final int MAX_LIST_LIMIT = 100;

    /** 快照带回的消息条数上限（对话栏展示用；单会话消息量小）。 */
    private static final int SNAPSHOT_MESSAGE_LIMIT = 200;
    /** history 双预算：条数 + 字符数。两个都必须有，否则「20 条超长消息」会撑爆信封。 */
    private static final int HISTORY_MAX_MESSAGES = 20;
    private static final int HISTORY_MAX_CHARS = 12_000;

    /** 学习目标枚举码：与前端 tab 里的 8 个选项一一对应，未识别回落 other。 */
    private static final Set<String> LEARNING_GOALS = Set.of(
            "exam_prep", "quiz_review", "homework", "understand_lesson",
            "take_notes", "generate_questions", "flashcards", "other");
    private static final String LEARNING_GOAL_DEFAULT = "take_notes";

    /**
     * 文本类输入的 UTF-8 字节上限（粘贴素材与单轮用户消息共用）。
     * <p>{@code mq_outbox.payload} 仍是 TEXT（65535 字节，见 026），而文本会整段进
     * {@code cmd.note.generate} 信封 —— 超限会让派发直接抛 MysqlDataTruncation。
     * 这里在 API 边界拦截，给出可执行的提示。
     */
    private static final int MAX_INPUT_TEXT_BYTES = 60_000;

    private final DemoNoteRepository repo;
    private final VerlaConversationService verlaConversationService;
    private final ObjectMapper objectMapper;

    // ============ 会话 ============

    /** 进入页面时的会话分配：复用最近的草稿，没有才新建（并在同事务建主线会话）。 */
    @Transactional
    public NoteConversation getOrCreateDraft(String clerkUserId, String outputLanguage) {
        NoteConversation draft = repo.findLatestDraft(clerkUserId).orElse(null);
        if (draft != null) {
            if (draft.getVerlaConversationId() == null) {
                draft.setVerlaConversationId(linkVerlaConversation(clerkUserId, draft.getTitle()));
                draft = repo.saveConversation(draft);
            }
            return draft;
        }
        return createConversation(clerkUserId, outputLanguage);
    }

    /** 解析会话标识：纯数字按 demo 主键（迁移期旧链接），{@code vc_*} 按主线 public id 反查。 */
    public Long resolveConversationId(String clerkUserId, String identifier) {
        String raw = identifier == null ? "" : identifier.trim();
        if (raw.isEmpty()) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "会话标识不能为空");
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            return getOwned(clerkUserId, Long.valueOf(raw)).getId();
        }
        Long verlaConversationId = VerlaPublicIdCodec.tryDecode(raw)
                .filter(publicId -> publicId.type() == VerlaPublicIdType.CONVERSATION)
                .map(VerlaPublicId::internalId)
                .orElseThrow(() -> new BusinessException(ApiCode.PARAM_ERROR.getCode(), "会话标识非法: " + raw));
        NoteConversation linked = repo.findByVerlaConversationId(verlaConversationId)
                .orElseThrow(() -> new BusinessException(ApiCode.NO_PERMISSION.getCode(), "会话不存在或无权访问"));
        return getOwned(clerkUserId, linked.getId()).getId();
    }

    public NoteConversation getOwned(String clerkUserId, Long conversationId) {
        return repo.getOwnedConversation(clerkUserId, conversationId)
                .orElseThrow(() -> new BusinessException(ApiCode.NO_PERMISSION.getCode(), "会话不存在或无权访问"));
    }

    /** 历史笔记列表：只列已提交过素材的记录，按最近活动倒序。 */
    public List<NoteConversation> listProcessedConversations(String clerkUserId, int limit) {
        return repo.listProcessedConversations(clerkUserId, Math.min(Math.max(limit, 1), MAX_LIST_LIMIT));
    }

    /** 对话栏消息（旧→新）。 */
    public List<NoteMessage> listMessages(Long conversationId) {
        return repo.listRecentMessages(conversationId, SNAPSHOT_MESSAGE_LIMIT);
    }

    /**
     * 本轮是否为首轮：请求带素材（objectId 或粘贴文本）即首轮。
     * <p>Python 侧用同一判据（payload 带 objectId/inputText）；两处必须一致，
     * 否则会出现「Java 以为后续轮、Python 以为首轮」地重复解析素材。
     */
    public static boolean isFirstTurnInput(String sourceType, String objectId, String text) {
        return blankToNull(objectId) != null || blankToNull(text) != null;
    }

    private NoteConversation createConversation(String clerkUserId, String outputLanguage) {
        NoteConversation c = new NoteConversation();
        c.setClerkUserId(clerkUserId);
        c.setTitle(DRAFT_TITLE);
        c.setStatus(STATUS_DRAFT);
        c.setVerlaConversationId(linkVerlaConversation(clerkUserId, DRAFT_TITLE, outputLanguage));
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return repo.saveConversation(c);
    }

    /**
     * 建主线 verla_conversations 行并返回其 id。
     * <p>输出语言写进 workspace_json（key 与 MQ payload 字段同名），派发时
     * {@code resolveOutputLanguage} 直接可用；demo 面向中文用户，缺省 chinese。
     */
    private Long linkVerlaConversation(String clerkUserId, String title) {
        return linkVerlaConversation(clerkUserId, title, null);
    }

    private Long linkVerlaConversation(String clerkUserId, String title, String outputLanguage) {
        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put(
                VerlaConversationService.WORKSPACE_KEY_OUTPUT_LANGUAGE,
                OutputLanguage.fromRaw(outputLanguage).getValue());
        String workspaceJson;
        try {
            workspaceJson = objectMapper.writeValueAsString(workspace);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize note workspace json", e);
        }
        Long verlaConversationId = verlaConversationService
                .create(clerkUserId, title, workspaceJson, PRIMARY_INTENT_NOTE)
                .getId();
        log.info("[Notes] 绑定主线会话: clerkUserId={}, verlaConversationId={}", clerkUserId, verlaConversationId);
        return verlaConversationId;
    }

    // ============ 回合准备 ============

    /**
     * 首轮准备：落素材 + 清空上一轮对话 + 把 query 落成第 1 条用户消息。
     * <p>清空消息是必要的：重新提交素材意味着旧的产物基线与对话上下文都作废，
     * 而 noteMd/noteVersion 同样被重置 —— 二者必须一起走，否则 history 会指向不存在的旧笔记。
     */
    @Transactional
    public NoteTurnPreparation prepareFirstTurn(String clerkUserId, Long conversationId,
                                                String sourceType, String objectId,
                                                String text, String filename,
                                                String courseName, String learningGoal,
                                                String query) {
        NoteConversation c = getOwned(clerkUserId, conversationId);
        String normalizedType = normalizeSourceType(sourceType, objectId, text);
        if (SOURCE_TYPE_FILE.equals(normalizedType)) {
            c.setSourceType(SOURCE_TYPE_FILE);
            c.setAttachmentObjectId(objectId.trim());
            c.setInputText(null);
            c.setTitle(truncate(filename == null || filename.isBlank() ? DRAFT_TITLE : filename.trim(),
                    TITLE_MAX_LENGTH));
        } else {
            String trimmed = text.trim();
            ensureTextFitsCommandPayload(trimmed, "粘贴文本");
            c.setSourceType(SOURCE_TYPE_TEXT);
            c.setAttachmentObjectId(null);
            c.setInputText(trimmed);
            c.setTitle(truncate(trimmed, TITLE_MAX_LENGTH));
        }
        c.setCourseName(truncateToNull(courseName, COURSE_NAME_MAX_LENGTH));
        c.setLearningGoal(normalizeLearningGoal(learningGoal));
        c.setNoteMd(null);
        // 新一轮生成重新计数：旧版本基线对本次结果不再有意义
        c.setNoteVersion(0L);
        c.setStatus(STATUS_GENERATING);
        repo.saveConversation(c);
        syncMainlineTitle(clerkUserId, c);

        repo.deleteMessages(c.getId());
        String q = blankToNull(query);
        if (q != null) {
            ensureTextFitsCommandPayload(q, "本轮提问");
            appendMessage(c, NoteMessage.ROLE_USER, NoteMessage.TYPE_TEXT, q);
        }
        return NoteTurnPreparation.builder().conversation(c).history(List.of()).build();
    }

    /**
     * 后续轮准备：校验消息 → **先取历史** → 落本轮用户消息 → 置 generating。
     * <p>顺序不能反：history 若在本轮消息落库之后取，会把当前这轮重复喂给模型。
     */
    @Transactional
    public NoteTurnPreparation prepareFollowUpTurn(String clerkUserId, Long conversationId, String message) {
        NoteConversation c = getOwned(clerkUserId, conversationId);
        String text = blankToNull(message);
        if (text == null) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "请输入内容后再发送");
        }
        ensureTextFitsCommandPayload(text, "本轮提问");
        List<Map<String, Object>> history = buildHistory(c.getId());
        appendMessage(c, NoteMessage.ROLE_USER, NoteMessage.TYPE_TEXT, text);
        c.setStatus(STATUS_GENERATING);
        repo.saveConversation(c);
        return NoteTurnPreparation.builder().conversation(c).history(history).build();
    }

    /**
     * 裁剪出下发给 Python 的会话历史（旧→新）。
     * <p>从最近往前累计，命中「条数上限」或「字符预算」即停 —— 保证拿到的是**连续**的最近若干轮。
     */
    public List<Map<String, Object>> buildHistory(Long conversationId) {
        List<NoteMessage> recent = repo.listRecentMessages(conversationId, HISTORY_MAX_MESSAGES);
        List<Map<String, Object>> picked = new ArrayList<>();
        int budget = HISTORY_MAX_CHARS;
        for (int i = recent.size() - 1; i >= 0; i--) {
            NoteMessage m = recent.get(i);
            String content = m.getContentMd() == null ? "" : m.getContentMd();
            if (content.isBlank()) {
                continue;
            }
            if (content.length() > budget) {
                break;
            }
            budget -= content.length();
            picked.add(0, Map.of(
                    "role", normalizeRole(m.getRole()),
                    "content", content));
        }
        return picked;
    }

    private NoteMessage appendMessage(NoteConversation c, String role, String type, String content) {
        NoteMessage m = new NoteMessage();
        m.setConversationId(c.getId());
        m.setRole(role);
        m.setMsgType(type);
        m.setContentMd(content);
        return repo.appendMessage(m);
    }

    private static String normalizeRole(String role) {
        return NoteMessage.ROLE_ASSISTANT.equals(role) ? NoteMessage.ROLE_ASSISTANT : NoteMessage.ROLE_USER;
    }

    // ============ 章节级改动提案（后续轮） ============

    /**
     * 落一份**待确认**提案（{@code NOTE_EDIT_PROPOSAL_READY}）。
     * <p>只写待审列：{@code note_md} 与 {@code note_version} 都**不动** —— 用户在编辑器里
     * 逐块接受/忽略后才由 {@link #commitProposal} 落库。
     * <p>同一会话只保留一份提案（新提案覆盖旧的，记日志）：审到一半基线又被改比丢掉提案更难排查。
     */
    @Transactional
    public void savePendingProposal(Long verlaConversationId, Map<String, Object> proposal) {
        if (proposal == null || proposal.isEmpty() || !proposal.containsKey("changes")) {
            log.warn("[Notes] proposal dropped: empty payload verlaConvId={}", verlaConversationId);
            return;
        }
        String proposalJson;
        try {
            proposalJson = objectMapper.writeValueAsString(proposal);
        } catch (JsonProcessingException e) {
            log.warn("[Notes] proposal dropped: un-serializable payload verlaConvId={}", verlaConversationId);
            return;
        }
        Long baseVersion = asLong(proposal.get("baseVersion"));
        NoteConversation c = repo.findByVerlaConversationId(verlaConversationId).orElse(null);
        if (c == null) {
            // 同 applyGeneratedTitle：提案丢了只是"没得审"，不该把 inbox cursor 卡住
            log.warn("[Notes] proposal dropped: no note conversation linked to verlaConvId={}",
                    verlaConversationId);
            return;
        }
        if (c.getPendingProposalJson() != null && !c.getPendingProposalJson().isBlank()) {
            log.info("[Notes] pending proposal replaced verlaConvId={}", verlaConversationId);
        }
        c.setPendingProposalJson(proposalJson);
        repo.saveConversation(c);
        log.info("[Notes] proposal staged verlaConvId={} baseVersion={}", verlaConversationId, baseVersion);
    }

    /**
     * 用户逐块决策后提交：只应用 **accepted** 的 hunk。
     * <ul>
     *   <li>乐观锁：{@code baseVersion} 与库中基线不一致 → 参数错误（前端刷新重审）</li>
     *   <li>全部接受 → 直接用提案里的完整 md（零歧义）；**部分接受** → 逐 hunk 按上下文定位替换</li>
     *   <li>一个都没接受 → 只清空提案，正文与版本都不动</li>
     * </ul>
     */
    @Transactional
    public NoteConversation commitProposal(String clerkUserId, Long conversationId, String proposalId,
                                           Long baseVersion, List<Map<String, Object>> decisions) {
        NoteConversation c = getOwned(clerkUserId, conversationId);
        String raw = c.getPendingProposalJson();
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "没有待确认的改动");
        }
        Map<String, Object> proposal = readJson(raw);
        if (proposalId == null || !proposalId.equals(str(proposal.get("proposalId")))) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "改动提案已更新，请刷新后重试");
        }
        long current = c.getNoteVersion() == null ? 0L : c.getNoteVersion();
        if (baseVersion != null && baseVersion != current) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "笔记版本已更新，请刷新后重试");
        }

        Map<String, String> decisionByHunk = new HashMap<>();
        for (Map<String, Object> decision : decisions == null ? List.<Map<String, Object>>of() : decisions) {
            String hunkId = str(decision.get("hunkId"));
            if (!hunkId.isBlank()) {
                decisionByHunk.put(hunkId, str(decision.get("status")).toLowerCase(Locale.ROOT));
            }
        }
        List<Map<String, Object>> changes = asHunkList(proposal.get("changes"));
        List<Map<String, Object>> accepted = changes.stream()
                .filter(hunk -> "accepted".equals(decisionByHunk.get(str(hunk.get("id")))))
                .toList();

        if (accepted.isEmpty()) {
            // 全拒绝：什么都不改，只把待审清掉
            c.setPendingProposalJson(null);
            repo.saveConversation(c);
            log.info("[Notes] proposal rejected verlaConvId={} hunks={}",
                    c.getVerlaConversationId(), changes.size());
            return c;
        }

        String nextMd;
        if (accepted.size() == changes.size()) {
            // 全接受：提案里那份完整 md 就是结果，不必逐块重放（避免定位歧义）
            nextMd = str(proposal.get("proposedContentMd"));
            if (nextMd.isBlank()) {
                throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "改动内容为空，请刷新后重试");
            }
        } else {
            nextMd = c.getNoteMd() == null ? "" : c.getNoteMd();
            for (Map<String, Object> hunk : accepted) {
                nextMd = applyHunk(nextMd, hunk);
            }
        }

        c.setNoteMd(nextMd);
        c.setNoteVersion(current + 1);
        c.setPendingProposalJson(null);
        c.setStatus(STATUS_COMPLETED);
        repo.saveConversation(c);
        log.info("[Notes] proposal committed verlaConvId={} accepted={}/{} version={}",
                c.getVerlaConversationId(), accepted.size(), changes.size(), c.getNoteVersion());
        return c;
    }

    /** 快照用：把待审提案解析成结构化数据（无提案返回 null）。 */
    public Map<String, Object> parsePendingProposal(NoteConversation c) {
        String raw = c == null ? null : c.getPendingProposalJson();
        return raw == null || raw.isBlank() ? null : readJson(raw);
    }

    /** 按「prefix + originalText + suffix」定位并替换；只凭 originalText 兜底，找不到就报错。 */
    private static String applyHunk(String md, Map<String, Object> hunk) {
        String original = str(hunk.get("originalText"));
        String proposed = str(hunk.get("proposedText"));
        Map<String, Object> anchor = asMap(hunk.get("anchor"));
        String prefix = str(anchor.get("prefix"));
        String suffix = str(anchor.get("suffix"));

        String needle = prefix + original + suffix;
        int index = (prefix.isEmpty() && suffix.isEmpty()) ? -1 : md.indexOf(needle);
        if (index >= 0) {
            return md.substring(0, index) + prefix + proposed + suffix + md.substring(index + needle.length());
        }
        int fallback = original.isEmpty() ? -1 : md.indexOf(original);
        if (fallback >= 0) {
            return md.substring(0, fallback) + proposed + md.substring(fallback + original.length());
        }
        // 定位不到 = 用户在这期间手改过：必须让前端刷新重审，不能静默跳过
        throw new BusinessException(ApiCode.PARAM_ERROR.getCode(),
                "笔记内容已变化，无法定位要修改的片段，请刷新后重试");
    }

    private Map<String, Object> readJson(String raw) {
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse note pending proposal", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asHunkList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ============ 用户编辑 / 结果回填 ============

    /**
     * AI 起的会话名（{@code NOTE_TITLE_RESOLVED}）：写 demo 表并同步主线表。
     * <p>两张表必须一起写：工作台左栏读 demo 表、Dashboard 的 AI Note 分区读主线表，
     * 只写一处就会出现「列表里一个名字、工作台里另一个」。
     * <p>只动标题，不碰 status / note_version（起名与生成进度无关）。
     * <p><b>刻意不抛异常</b>（与 {@link #applyArtifactCommit} 相反）：正文丢了是数据事故，
     * 而标题丢了只是不好看 —— handler 抛异常会让该行标 FAILED 且 cursor 不推进，
     * 用一次"好不好看"的失败把整条会话卡住不划算，所以这里降级成可 grep 的 WARN。
     */
    @Transactional
    public void applyGeneratedTitle(Long verlaConversationId, String title) {
        String trimmed = blankToNull(title);
        if (trimmed == null) {
            return;
        }
        NoteConversation c = repo.findByVerlaConversationId(verlaConversationId).orElse(null);
        if (c == null) {
            log.warn("[Notes] title dropped: no note conversation linked to verlaConvId={}",
                    verlaConversationId);
            return;
        }
        String next = truncate(trimmed, TITLE_MAX_LENGTH);
        c.setTitle(next);
        repo.saveConversation(c);
        syncMainlineTitle(c.getClerkUserId(), c);
        log.info("[Notes] generated title applied verlaConvId={} title={}", verlaConversationId, next);
    }


    /**
     * 用户手改保存：以传入 baseVersion 做乐观锁，成功后版本 +1。
     * <p>与 AI 写入（{@link #applyArtifactCommit}）分开：AI 写入不能被记成用户编辑，
     * 用户编辑也不能覆盖 AI 之后的新版本 —— 版本不匹配时让前端重新拉快照再重试。
     */
    @Transactional
    public NoteConversation saveUserNote(String clerkUserId, Long conversationId,
                                         String contentMd, Long baseVersion) {
        NoteConversation c = getOwned(clerkUserId, conversationId);
        if (contentMd == null || contentMd.isBlank()) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "contentMd 不能为空");
        }
        long current = c.getNoteVersion() == null ? 0L : c.getNoteVersion();
        if (baseVersion != null && baseVersion != current) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "笔记版本已更新，请刷新后重试");
        }
        c.setNoteMd(contentMd);
        c.setNoteVersion(current + 1);
        if (!STATUS_COMPLETED.equals(c.getStatus())) {
            c.setStatus(STATUS_COMPLETED);
        }
        return repo.saveConversation(c);
    }

    /**
     * AI 写入文档（{@code NOTE_ARTIFACT_COMMIT}）：落最新 md 并推进版本。
     * <p><b>版本以库中最新基线为准</b>：Python 回显的 {@code versionNo} 只用于对账日志 ——
     * 用户可能在 AI 回合中途 PATCH 过，若按 Python 的号覆盖会把用户编辑的版本抹掉。
     */
    @Transactional
    public void applyArtifactCommit(Long verlaConversationId, String contentMd, Long reportedVersionNo) {
        NoteConversation c = repo.findByVerlaConversationId(verlaConversationId).orElse(null);
        if (c == null) {
            throw new IllegalStateException(
                    "Notes conversation not linked to verla conversation " + verlaConversationId);
        }
        if (contentMd == null || contentMd.isBlank()) {
            throw new IllegalStateException(
                    "NOTE_ARTIFACT_COMMIT without contentMd, verlaConversationId=" + verlaConversationId);
        }
        long next = (c.getNoteVersion() == null ? 0L : c.getNoteVersion()) + 1;
        c.setNoteMd(contentMd);
        c.setNoteVersion(next);
        c.setStatus(STATUS_COMPLETED);
        repo.saveConversation(c);
        if (reportedVersionNo != null && reportedVersionNo != next) {
            // 对账：不一致通常意味着用户在 AI 回合中途手改过（AI 与用户各自推进版本）
            log.warn("[Notes] artifact version reconciled verlaConvId={} pyVersion={} dbVersion={}",
                    verlaConversationId, reportedVersionNo, next);
        }
        log.info("[Notes] artifact committed verlaConvId={} version={} chars={}",
                verlaConversationId, next, contentMd.length());
    }

    /**
     * 回合终态（{@code NOTE_TURN_COMPLETED}）：追加助手消息并把会话置为 completed。
     * <p>没有助手文本时不追加空气泡（例如弱模型「只调工具不说话」）。
     */
    @Transactional
    public void completeTurn(Long verlaConversationId, String assistantText) {
        NoteConversation c = repo.findByVerlaConversationId(verlaConversationId).orElse(null);
        if (c == null) {
            throw new IllegalStateException(
                    "Notes conversation not linked to verla conversation " + verlaConversationId);
        }
        String text = assistantText == null ? "" : assistantText.trim();
        if (!text.isEmpty()) {
            appendMessage(c, NoteMessage.ROLE_ASSISTANT, NoteMessage.TYPE_TEXT, text);
        }
        c.setStatus(STATUS_COMPLETED);
        repo.saveConversation(c);
    }

    /** 失败态是 best-effort：只影响前端提示，不阻塞事件 drain。 */
    @Transactional
    public void markFailed(Long verlaConversationId) {
        repo.findByVerlaConversationId(verlaConversationId).ifPresent(c -> {
            c.setStatus(STATUS_FAILED);
            repo.saveConversation(c);
        });
    }

    // ============ helpers ============

    /**
     * 把 demo 侧的标题（文件名 / 粘贴文本）同步到**主线会话**。
     * <p>Dashboard 的「AI Note」分区与 Recent 卡片读的是 {@code verla_conversations.title}
     * （走 {@code /v1/verla/conversations?segment=note}），而它只在建会话时写过一次
     * 「未命名笔记」—— 不同步的话列表里永远是「未命名笔记」，尽管工作台左栏
     * （读 demo 表）显示的是文件名。
     * <p>best-effort：标题只是展示信息，同步失败不该让整轮生成失败。
     */
    private void syncMainlineTitle(String clerkUserId, NoteConversation c) {
        Long verlaConversationId = c.getVerlaConversationId();
        String title = blankToNull(c.getTitle());
        // 还是草稿占位标题就没必要写（没提交过素材的会话本来就该叫未命名笔记）
        if (verlaConversationId == null || title == null || DRAFT_TITLE.equals(title)) {
            return;
        }
        try {
            verlaConversationService.rename(clerkUserId, verlaConversationId, title);
        } catch (RuntimeException ex) {
            log.warn("[Notes] 主线标题同步失败 verlaConvId={} title={} reason={}",
                    verlaConversationId, title, ex.getMessage());
        }
    }

    private static String normalizeSourceType(String sourceType, String objectId, String text) {
        String raw = sourceType == null ? "" : sourceType.trim().toLowerCase();
        if (SOURCE_TYPE_FILE.equals(raw)) {
            requireFileObjectId(objectId);
            return SOURCE_TYPE_FILE;
        }
        if (SOURCE_TYPE_TEXT.equals(raw)) {
            requireText(text);
            return SOURCE_TYPE_TEXT;
        }
        if (raw.isEmpty()) {
            if (objectId != null && !objectId.isBlank()) {
                requireFileObjectId(objectId);
                return SOURCE_TYPE_FILE;
            }
            requireText(text);
            return SOURCE_TYPE_TEXT;
        }
        throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "sourceType 只支持 file 或 text");
    }

    private static void requireFileObjectId(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "选择文件上传时必须提供 objectId");
        }
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "必须上传文件或粘贴文本");
        }
    }

    private static void ensureTextFitsCommandPayload(String text, String label) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_INPUT_TEXT_BYTES) {
            throw new BusinessException(
                    ApiCode.PARAM_ERROR.getCode(),
                    label + "过长（" + bytes + " 字节，上限 " + MAX_INPUT_TEXT_BYTES + "），请精简后重试");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 空值给默认目标；未知码回落 other（前端先行上线新目标时不至于报错）。 */
    private static String normalizeLearningGoal(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return LEARNING_GOAL_DEFAULT;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (LEARNING_GOALS.contains(normalized)) {
            return normalized;
        }
        log.warn("[Notes] unknown learningGoal={} → 回落 other", raw);
        return "other";
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /** 截断且空值保持 null（标题用 truncate 的 "" 语义，可选字段用这个）。 */
    private static String truncateToNull(String value, int maxLength) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
