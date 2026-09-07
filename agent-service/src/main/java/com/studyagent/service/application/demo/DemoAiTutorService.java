package com.studyagent.service.application.demo;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
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
 * 用户态编排：会话/消息/文档/版本/引用证据；LLM 与主循环在 verla_agent（MQ），M0 由 Controller 提供 mock 流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoAiTutorService {

    private final DemoAiTutorRepository repo;

    // ============ 会话 ============

    @Transactional
    public AiTutorConversation createConversation(String clerkUserId, String initialQuery, String paperMetaJson) {
        AiTutorConversation c = new AiTutorConversation();
        c.setClerkUserId(clerkUserId);
        c.setInitialQuery(initialQuery);
        c.setTitle(initialQuery.length() > 40 ? initialQuery.substring(0, 40) : initialQuery);
        c.setPaperMeta(paperMetaJson);
        c.setStatus("active");
        c.setBaseVersion(0L);
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return repo.saveConversation(c);
    }

    public List<AiTutorConversation> listConversations(String clerkUserId, int limit) {
        return repo.listConversations(clerkUserId, Math.min(Math.max(limit, 1), 100));
    }

    public AiTutorConversation getOwned(String clerkUserId, Long conversationId) {
        return repo.getOwnedConversation(clerkUserId, conversationId)
                .orElseThrow(() -> new BusinessException(ApiCode.NO_PERMISSION, "会话不存在或无权访问"));
    }

    @Transactional
    public AiTutorConversation updatePaperMeta(String clerkUserId, Long conversationId, String paperMetaJson) {
        AiTutorConversation c = getOwned(clerkUserId, conversationId);
        c.setPaperMeta(paperMetaJson);
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
