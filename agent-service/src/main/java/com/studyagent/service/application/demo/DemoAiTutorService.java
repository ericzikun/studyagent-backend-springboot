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
import com.studyagent.service.domain.demo.aitutor.AiTutorConversation;
import com.studyagent.service.domain.demo.aitutor.AiTutorDocument;
import com.studyagent.service.domain.demo.aitutor.AiTutorDocVersion;
import com.studyagent.service.domain.demo.aitutor.AiTutorEvidence;
import com.studyagent.service.domain.demo.aitutor.AiTutorMessage;
import com.studyagent.service.domain.demo.aitutor.repo.DemoAiTutorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tutor（学术论文写作 Copilot）demo 业务服务。
 * <p>用户态编排：会话 / 消息 / 文档 / 版本 / 引用证据。LLM 与主循环在 verla_agent（MQ），
 * 事件回流走主线 Verla 通道（verla_event_inbox → SSE），demo 不再自建 SSE 桥接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoAiTutorService {

    /**
     * 主线 verla_conversations.primary_intent 取值。
     * <p>必须是非空且不属于任何已知分栏的值：VerlaConversationMapper 把
     * {@code primary_intent IS NULL OR = ''} 归入 {@code segment="assignment"}，
     * 传 null 会让 demo 会话出现在 Dashboard 的作业分栏里。
     */
    private static final String PRIMARY_INTENT_AI_TUTOR = "AI_TUTOR";

    /** 进入页面即分配会话时的占位标题；首条消息到达后会被真实主题替换。 */
    private static final String DRAFT_TITLE = "新对话";
    private static final int TITLE_MAX_LENGTH = 40;
    private static final int INITIAL_QUERY_MAX_LENGTH = 1024;

    private final DemoAiTutorRepository repo;
    private final VerlaConversationService verlaConversationService;
    private final ObjectMapper objectMapper;

    // ============ 会话 ============

    @Transactional
    public AiTutorConversation createConversation(String clerkUserId, String initialQuery, String sessionContextJson) {
        return createConversation(clerkUserId, initialQuery, truncate(initialQuery, TITLE_MAX_LENGTH), sessionContextJson);
    }

    /**
     * 进入 AI Tutor 页面时的会话分配：复用最近的未使用草稿，没有才新建。
     * <p>「未使用」= 既无消息也无文档：用户只要发过一轮或产生过产物，就不再是草稿。
     */
    @Transactional
    public AiTutorConversation getOrCreateDraftConversation(String clerkUserId) {
        AiTutorConversation draft = repo.findLatestUnusedConversation(clerkUserId).orElse(null);
        if (draft == null) {
            return createConversation(clerkUserId, "", DRAFT_TITLE, null);
        }
        if (draft.getVerlaConversationId() == null) {
            String title = draft.getTitle() == null || draft.getTitle().isBlank() ? DRAFT_TITLE : draft.getTitle();
            draft.setVerlaConversationId(linkVerlaConversation(clerkUserId, title));
            draft = repo.saveConversation(draft);
        }
        return draft;
    }

    /**
     * 解析会话标识：纯数字按 demo 主键（迁移期旧链接），{@code vc_*} 按主线 public id 反查 demo 会话。
     * <p>解析结果仍走 {@link #getOwned}，public id 不能绕过归属校验。
     */
    public Long resolveConversationId(String clerkUserId, String identifier) {
        String raw = identifier == null ? "" : identifier.trim();
        if (raw.isEmpty()) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "会话标识不能为空");
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            return getOwned(clerkUserId, Long.valueOf(raw)).getId();
        }
        Long verlaConversationId = VerlaPublicIdCodec.tryDecode(raw)
                .filter(publicId -> publicId.type() == VerlaPublicIdType.CONVERSATION)
                .map(VerlaPublicId::internalId)
                .orElseThrow(() -> new BusinessException(ApiCode.PARAM_ERROR, "会话标识非法: " + raw));
        AiTutorConversation linked = repo.findByVerlaConversationId(verlaConversationId)
                .orElseThrow(() -> new BusinessException(ApiCode.NO_PERMISSION, "会话不存在或无权访问"));
        return getOwned(clerkUserId, linked.getId()).getId();
    }

    /**
     * 首条用户消息：确定标题与初始目标，并把本轮会话上下文写入会话。
     * <p>草稿标题是占位值，派发 payload 的 sessionTitle 与主线历史都读标题，必须在派发前落库并同步主线。
     */
    @Transactional
    public AiTutorConversation applyFirstMessage(String clerkUserId, AiTutorConversation c,
                                                 String message, String sessionContextJson) {
        if (c.getTitle() == null || c.getTitle().isBlank() || DRAFT_TITLE.equals(c.getTitle())) {
            String title = truncate(message, TITLE_MAX_LENGTH);
            c.setTitle(title);
            if (c.getVerlaConversationId() != null) {
                try {
                    verlaConversationService.rename(clerkUserId, c.getVerlaConversationId(), title);
                } catch (BusinessException ex) {
                    // 主线标题只影响历史与排障，不同步失败不应拦住本轮消息
                    log.warn("[AI-Tutor] 同步主线会话标题失败: verlaConversationId={}, cause={}",
                            c.getVerlaConversationId(), ex.getMessage());
                }
            }
        }
        if (c.getInitialQuery() == null || c.getInitialQuery().isBlank()) {
            c.setInitialQuery(truncate(message, INITIAL_QUERY_MAX_LENGTH));
        }
        if (sessionContextJson != null) {
            c.setSessionContext(sessionContextJson);
        }
        c.setUpdatedAt(LocalDateTime.now());
        return repo.saveConversation(c);
    }

    private AiTutorConversation createConversation(String clerkUserId, String initialQuery,
                                                   String title, String sessionContextJson) {
        AiTutorConversation c = new AiTutorConversation();
        c.setClerkUserId(clerkUserId);
        c.setInitialQuery(initialQuery);
        c.setTitle(title);
        c.setSessionContext(sessionContextJson);
        c.setStatus("active");
        c.setBaseVersion(0L);
        c.setVerlaConversationId(linkVerlaConversation(clerkUserId, title));
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return repo.saveConversation(c);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /**
     * 兼容 sql/083 之前创建的 demo 会话：缺主线会话时补建并回填。
     */
    @Transactional
    public AiTutorConversation ensureVerlaLink(String clerkUserId, Long conversationId) {
        AiTutorConversation c = getOwned(clerkUserId, conversationId);
        if (c.getVerlaConversationId() != null) {
            return c;
        }
        c.setVerlaConversationId(linkVerlaConversation(clerkUserId, c.getTitle()));
        return repo.saveConversation(c);
    }

    /**
     * 建主线 verla_conversations 行并返回其 id。
     * <p>输出语言偏好写进 workspace_json（key 与 MQ payload 字段同名），
     * 派发命令时 {@code resolveOutputLanguage} 直接可用；demo 面向中文学术写作，缺省 chinese。
     */
    private Long linkVerlaConversation(String clerkUserId, String title) {
        Map<String, Object> workspace = Map.of(
                VerlaConversationService.WORKSPACE_KEY_OUTPUT_LANGUAGE,
                OutputLanguage.CHINESE.getValue());
        String workspaceJson;
        try {
            workspaceJson = objectMapper.writeValueAsString(workspace);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize AI Tutor workspace json", e);
        }
        Long verlaConversationId = verlaConversationService
                .create(clerkUserId, title, workspaceJson, PRIMARY_INTENT_AI_TUTOR)
                .getId();
        log.info("[AI-Tutor] 绑定主线会话: clerkUserId={}, verlaConversationId={}", clerkUserId, verlaConversationId);
        return verlaConversationId;
    }

    public List<AiTutorConversation> listConversations(String clerkUserId, int limit) {
        return repo.listConversations(clerkUserId, Math.min(Math.max(limit, 1), 100));
    }

    public AiTutorConversation getOwned(String clerkUserId, Long conversationId) {
        return repo.getOwnedConversation(clerkUserId, conversationId)
                .orElseThrow(() -> new BusinessException(ApiCode.NO_PERMISSION, "会话不存在或无权访问"));
    }

    @Transactional
    public AiTutorConversation updateSessionContext(String clerkUserId, Long conversationId, String sessionContextJson) {
        AiTutorConversation c = getOwned(clerkUserId, conversationId);
        c.setSessionContext(sessionContextJson);
        repo.saveConversation(c);
        return c;
    }

    // ============ 消息 ============

    @Transactional
    public AiTutorMessage appendMessage(Long conversationId, String role, String msgType, String contentMd) {
        AiTutorMessage m = new AiTutorMessage();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setMsgType(msgType == null ? "text" : msgType);
        m.setContentMd(contentMd);
        m.setSeq((long) (repo.listMessages(conversationId).size() + 1));
        m.setCreatedAt(LocalDateTime.now());
        repo.touchConversationUpdatedAt(conversationId);
        return repo.appendMessage(m);
    }

    public List<AiTutorMessage> listMessages(Long conversationId) {
        return repo.listMessages(conversationId);
    }

    // ============ 文档/版本 ============

    /** 用户手改保存：以传入 baseVersion 为准，写入 user 版本。 */
    @Transactional
    public AiTutorDocument saveUserDocument(Long conversationId, String contentMd, Long baseVersion) {
        AiTutorDocument doc = repo.getDocument(conversationId)
                .orElseGet(() -> {
                    AiTutorDocument d = new AiTutorDocument();
                    d.setConversationId(conversationId);
                    d.setContentMd("");
                    d.setBaseVersion(0L);
                    d.setUpdatedBy("user");
                    return d;
                });
        if (baseVersion != null && !baseVersion.equals(doc.getBaseVersion())) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "文档版本冲突，请刷新后重试");
        }
        AiTutorDocVersion ver = new AiTutorDocVersion();
        ver.setDocumentId(doc.getId() == null ? -1L : doc.getId());
        ver.setVersionNo(doc.getBaseVersion() + 1);
        ver.setSource("user");
        ver.setContentMd(contentMd);
        ver.setCreatedAt(LocalDateTime.now());
        doc.setContentMd(contentMd);
        doc.setUpdatedBy("user");
        doc.setBaseVersion(doc.getBaseVersion() + 1);
        AiTutorDocument saved = repo.saveDocumentWithVersion(doc, ver);
        repo.touchConversationUpdatedAt(conversationId);
        return saved;
    }

    /** AI 写入文档：基于最新基线（含用户手改）生成 ai 版本并落快照。 */
    @Transactional
    public AiTutorDocument saveAiUpdate(Long conversationId, String contentMd) {
        AiTutorDocument doc = repo.getDocument(conversationId).orElseGet(() -> {
            AiTutorDocument d = new AiTutorDocument();
            d.setConversationId(conversationId);
            d.setContentMd("");
            d.setBaseVersion(0L);
            d.setUpdatedBy("ai");
            return d;
        });
        AiTutorDocVersion ver = new AiTutorDocVersion();
        ver.setDocumentId(doc.getId() == null ? -1L : doc.getId());
        ver.setVersionNo(doc.getBaseVersion() + 1);
        ver.setSource("ai");
        ver.setContentMd(contentMd);
        ver.setCreatedAt(java.time.LocalDateTime.now());
        doc.setContentMd(contentMd);
        doc.setUpdatedBy("ai");
        doc.setBaseVersion(doc.getBaseVersion() + 1);
        AiTutorDocument saved = repo.saveDocumentWithVersion(doc, ver);
        repo.touchConversationUpdatedAt(conversationId);
        return saved;
    }

    public AiTutorDocument undo(Long conversationId) {
        AiTutorDocument doc = repo.getDocument(conversationId)
                .orElseThrow(() -> new BusinessException(ApiCode.ILLEGAL_STATE, "尚无文档"));
        long target = Math.max(0, doc.getBaseVersion() - 1);
        return repo.applyVersion(conversationId, target);
    }

    public AiTutorDocument redo(Long conversationId) {
        AiTutorDocument doc = repo.getDocument(conversationId)
                .orElseThrow(() -> new BusinessException(ApiCode.ILLEGAL_STATE, "尚无文档"));
        return repo.applyVersion(conversationId, doc.getBaseVersion() + 1);
    }

    // ============ 引用证据 ============

    @Transactional
    public AiTutorEvidence addUserMaterial(Long conversationId, String title, String content) {
        AiTutorEvidence e = new AiTutorEvidence();
        e.setConversationId(conversationId);
        e.setSourceType("user");
        e.setTitle(title == null || title.isBlank() ? content.substring(0, Math.min(content.length(), 80)) : title);
        e.setSnippet(content);
        e.setConfirmed(Boolean.FALSE);
        e.setCreatedAt(LocalDateTime.now());
        return repo.addEvidence(e);
    }

    @Transactional
    public List<AiTutorEvidence> confirmEvidences(Long conversationId, List<Long> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "evidenceIds 不能为空");
        }
        Long next = 1L;
        List<AiTutorEvidence> all = repo.listEvidences(conversationId);
        for (AiTutorEvidence e : all) {
            if (Boolean.TRUE.equals(e.getConfirmed())) {
                next = Math.max(next, (e.getSeqNo() == null ? 0L : e.getSeqNo()) + 1);
            }
        }
        for (Long id : evidenceIds) {
            AiTutorEvidence e = repo.getEvidence(id)
                    .orElseThrow(() -> new BusinessException(ApiCode.PARAM_ERROR, "证据不存在: " + id));
            if (!e.getConversationId().equals(conversationId)) {
                throw new BusinessException(ApiCode.NO_PERMISSION, "证据不属于该会话");
            }
            if (!Boolean.TRUE.equals(e.getConfirmed())) {
                e.setConfirmed(Boolean.TRUE);
                e.setSeqNo(next++);
                repo.updateEvidence(e);
            }
        }
        return repo.listEvidences(conversationId);
    }

    public com.studyagent.service.domain.demo.aitutor.AiTutorDocument getDocumentForConversation(Long conversationId) {
        return repo.getDocument(conversationId).orElse(null);
    }

    // ============ 快照 ============

    public Map<String, Object> snapshot(String clerkUserId, Long conversationId) {
        AiTutorConversation c = getOwned(clerkUserId, conversationId);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("conversation", c);
        snap.put("messages", repo.listMessages(conversationId));
        snap.put("document", repo.getDocument(conversationId).orElse(null));
        AiTutorDocument doc = repo.getDocument(conversationId).orElse(null);
        if (doc != null && doc.getId() != null) {
            // 版本列表简化为最近 50 条
            List<AiTutorDocVersion> versions = repo.listDocumentVersions(doc.getId());
            int from = Math.max(0, versions.size() - 50);
            snap.put("versions", versions.subList(from, versions.size()));
        } else {
            snap.put("versions", List.of());
        }
        snap.put("evidences", repo.listEvidences(conversationId));
        snap.put("state", Map.of());
        return snap;
    }
}
