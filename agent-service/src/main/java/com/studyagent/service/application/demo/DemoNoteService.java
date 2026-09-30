package com.studyagent.service.application.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.enums.OutputLanguage;
import com.studyagent.common.verla.id.VerlaPublicId;
import com.studyagent.common.verla.id.VerlaPublicIdCodec;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.domain.demo.note.NoteConversation;
import com.studyagent.service.domain.demo.note.repo.DemoNoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 笔记 demo 业务服务：会话编排（素材提交 / 结果回填）。
 * <p>解析与单轮 LLM 都在 verla_agent（MQ 命令），事件回流走主线 Verla 通道；
 * 本服务只维护 demo 自己的会话行与产物。
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
    /** 历史列表单页上限（单用户记录量小，暂不做游标分页）。 */
    private static final int MAX_LIST_LIMIT = 100;

    /**
     * 粘贴文本的 UTF-8 字节上限。
     * <p>{@code mq_outbox.payload} 仍是 TEXT（65535 字节，见 026），而粘贴文本会整段进
     * {@code cmd.note.generate} 信封 —— 超限会让派发直接抛 MysqlDataTruncation。
     * 这里在 API 边界拦截，给出可执行的提示（改用文件上传）。
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

    // ============ 素材提交 / 结果回填 ============

    /**
     * 落库本次提交的素材并置为 generating。调用方随后在同一次请求里派发命令。
     *
     * @return 更新后的会话（含解析出的素材类型）
     */
    @Transactional
    public NoteConversation prepareGeneration(String clerkUserId, Long conversationId,
                                              String sourceType, String objectId,
                                              String text, String filename) {
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
            ensureTextFitsCommandPayload(trimmed);
            c.setSourceType(SOURCE_TYPE_TEXT);
            c.setAttachmentObjectId(null);
            c.setInputText(trimmed);
            c.setTitle(truncate(trimmed, TITLE_MAX_LENGTH));
        }
        c.setNoteMd(null);
        c.setStatus(STATUS_GENERATING);
        return repo.saveConversation(c);
    }

    /** 事件回填：由主线 conversationId 反查 demo 行（事件里没有 demo 主键）。 */
    @Transactional
    public void applyGeneratedNote(Long verlaConversationId, String noteMd, String title) {
        NoteConversation c = repo.findByVerlaConversationId(verlaConversationId).orElse(null);
        if (c == null) {
            throw new IllegalStateException(
                    "Notes conversation not linked to verla conversation " + verlaConversationId);
        }
        c.setNoteMd(noteMd);
        if (title != null && !title.isBlank()) {
            c.setTitle(truncate(title.trim(), TITLE_MAX_LENGTH));
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

    private static void ensureTextFitsCommandPayload(String text) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_INPUT_TEXT_BYTES) {
            throw new BusinessException(
                    ApiCode.PARAM_ERROR,
                    "粘贴文本过长（" + bytes + " 字节，上限 " + MAX_INPUT_TEXT_BYTES + "），请改用文件上传");
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
