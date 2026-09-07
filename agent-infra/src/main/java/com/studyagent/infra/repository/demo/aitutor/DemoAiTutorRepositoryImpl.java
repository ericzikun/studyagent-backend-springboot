package com.studyagent.infra.repository.demo.aitutor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.infra.entity.demo.aitutor.DemoAiTutorConversationEntity;
import com.studyagent.infra.entity.demo.aitutor.DemoAiTutorDocVersionEntity;
import com.studyagent.infra.entity.demo.aitutor.DemoAiTutorDocumentEntity;
import com.studyagent.infra.entity.demo.aitutor.DemoAiTutorEvidenceEntity;
import com.studyagent.infra.entity.demo.aitutor.DemoAiTutorMessageEntity;
import com.studyagent.infra.mapper.demo.aitutor.DemoAiTutorConversationMapper;
import com.studyagent.infra.mapper.demo.aitutor.DemoAiTutorDocVersionMapper;
import com.studyagent.infra.mapper.demo.aitutor.DemoAiTutorDocumentMapper;
import com.studyagent.infra.mapper.demo.aitutor.DemoAiTutorEvidenceMapper;
import com.studyagent.infra.mapper.demo.aitutor.DemoAiTutorMessageMapper;
import com.studyagent.service.domain.demo.aitutor.AiTutorConversation;
import com.studyagent.service.domain.demo.aitutor.AiTutorDocument;
import com.studyagent.service.domain.demo.aitutor.AiTutorDocVersion;
import com.studyagent.service.domain.demo.aitutor.AiTutorEvidence;
import com.studyagent.service.domain.demo.aitutor.AiTutorMessage;
import com.studyagent.service.domain.demo.aitutor.repo.DemoAiTutorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** AI Tutor demo 仓库实现：MP 实体 <-> 领域对象互转 */
@Repository
@RequiredArgsConstructor
public class DemoAiTutorRepositoryImpl implements DemoAiTutorRepository {

    private final DemoAiTutorConversationMapper convMapper;
    private final DemoAiTutorMessageMapper msgMapper;
    private final DemoAiTutorDocumentMapper docMapper;
    private final DemoAiTutorDocVersionMapper verMapper;
    private final DemoAiTutorEvidenceMapper evMapper;

    // ---- conversation ----
    @Override
    public AiTutorConversation saveConversation(AiTutorConversation c) {
        DemoAiTutorConversationEntity e = toEntity(c);
        if (c.getId() == null) {
            convMapper.insert(e);
            c.setId(e.getId());
        } else {
            convMapper.updateById(e);
        }
        return c;
    }

    @Override
    public List<AiTutorConversation> listConversations(String clerkUserId, int limit) {
        return convMapper.selectList(new LambdaQueryWrapper<DemoAiTutorConversationEntity>()
                        .eq(DemoAiTutorConversationEntity::getClerkUserId, clerkUserId)
                        .orderByDesc(DemoAiTutorConversationEntity::getUpdatedAt)
                        .last("LIMIT " + limit))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<AiTutorConversation> getOwnedConversation(String clerkUserId, Long conversationId) {
        DemoAiTutorConversationEntity e = convMapper.selectOne(new LambdaQueryWrapper<DemoAiTutorConversationEntity>()
                .eq(DemoAiTutorConversationEntity::getId, conversationId)
                .eq(DemoAiTutorConversationEntity::getClerkUserId, clerkUserId));
        return Optional.ofNullable(e).map(this::toDomain);
    }

    @Override
    public void touchConversationUpdatedAt(Long conversationId) {
        DemoAiTutorConversationEntity e = convMapper.selectById(conversationId);
        if (e != null) {
            e.setUpdatedAt(java.time.LocalDateTime.now());
            convMapper.updateById(e);
        }
    }

    // ---- message ----
    @Override
    public AiTutorMessage appendMessage(AiTutorMessage m) {
        DemoAiTutorMessageEntity e = toEntity(m);
        msgMapper.insert(e);
        m.setId(e.getId());
        return m;
    }

    @Override
    public List<AiTutorMessage> listMessages(Long conversationId) {
        return msgMapper.selectList(new LambdaQueryWrapper<DemoAiTutorMessageEntity>()
                        .eq(DemoAiTutorMessageEntity::getConversationId, conversationId)
                        .orderByAsc(DemoAiTutorMessageEntity::getSeq))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    // ---- evidence ----
    @Override
    public AiTutorEvidence addEvidence(AiTutorEvidence e) {
        DemoAiTutorEvidenceEntity ent = toEntity(e);
        evMapper.insert(ent);
        e.setId(ent.getId());
        return e;
    }

    @Override
    public AiTutorEvidence updateEvidence(AiTutorEvidence e) {
        evMapper.updateById(toEntity(e));
        return e;
    }

    @Override
    public List<AiTutorEvidence> listEvidences(Long conversationId) {
        return evMapper.selectList(new LambdaQueryWrapper<DemoAiTutorEvidenceEntity>()
                        .eq(DemoAiTutorEvidenceEntity::getConversationId, conversationId)
                        .orderByAsc(DemoAiTutorEvidenceEntity::getSeqNo))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<AiTutorEvidence> getEvidence(Long evidenceId) {
        return Optional.ofNullable(evMapper.selectById(evidenceId)).map(this::toDomain);
    }

    // ---- document / version ----
    @Override
    public Optional<AiTutorDocument> getDocument(Long conversationId) {
        DemoAiTutorDocumentEntity e = docMapper.selectOne(new LambdaQueryWrapper<DemoAiTutorDocumentEntity>()
                .eq(DemoAiTutorDocumentEntity::getConversationId, conversationId));
        return Optional.ofNullable(e).map(this::toDomain);
    }

    @Override
    @Transactional
    public AiTutorDocument saveDocumentWithVersion(AiTutorDocument doc, AiTutorDocVersion version) {
        DemoAiTutorDocumentEntity de = doc.getId() == null ? toEntity(doc) : docMapper.selectById(doc.getId());
        if (de == null) {
            de = toEntity(doc);
            de.setCreatedAt(java.time.LocalDateTime.now());
            docMapper.insert(de);
            doc.setId(de.getId());
            version.setDocumentId(de.getId());
        } else {
            de.setContentMd(doc.getContentMd());
            de.setUpdatedBy(doc.getUpdatedBy());
            de.setBaseVersion(doc.getBaseVersion());
            de.setUpdatedAt(java.time.LocalDateTime.now());
            docMapper.updateById(de);
        }
        if (version.getDocumentId() != null && version.getDocumentId() > 0) {
            DemoAiTutorDocVersionEntity ve = toEntity(version);
            verMapper.insert(ve);
            version.setId(ve.getId());
        }
        return getDocument(doc.getConversationId()).orElse(doc);
    }

    @Override
    public AiTutorDocVersion getDocumentVersion(Long documentId, Long versionNo) {
        DemoAiTutorDocVersionEntity e = verMapper.selectOne(new LambdaQueryWrapper<DemoAiTutorDocVersionEntity>()
                .eq(DemoAiTutorDocVersionEntity::getDocumentId, documentId)
                .eq(DemoAiTutorDocVersionEntity::getVersionNo, versionNo));
        return e == null ? null : toDomain(e);
    }

    @Override
    public List<AiTutorDocVersion> listDocumentVersions(Long documentId) {
        return verMapper.selectList(new LambdaQueryWrapper<DemoAiTutorDocVersionEntity>()
                        .eq(DemoAiTutorDocVersionEntity::getDocumentId, documentId)
                        .orderByAsc(DemoAiTutorDocVersionEntity::getVersionNo))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public AiTutorDocument applyVersion(Long conversationId, Long versionNo) {
        AiTutorDocument doc = getDocument(conversationId)
                .orElseThrow(() -> new IllegalStateException("no document"));
        List<AiTutorDocVersion> versions = listDocumentVersions(doc.getId());
        if (versions.isEmpty()) {
            return doc;
        }
        AiTutorDocVersion target = versions.stream()
                .filter(v -> v.getVersionNo().equals(versionNo))
                .findFirst().orElse(null);
        if (target == null) {
            // 越界：取最近可用边界
            target = versionNo <= 0 ? versions.get(0) : versions.get(versions.size() - 1);
        }
        DemoAiTutorDocumentEntity de = docMapper.selectById(doc.getId());
        de.setContentMd(target.getContentMd());
        de.setBaseVersion(target.getVersionNo());
        de.setUpdatedBy("user");
        de.setUpdatedAt(java.time.LocalDateTime.now());
        docMapper.updateById(de);
        return getDocument(conversationId).orElse(doc);
    }

    // ---- 转换 ----
    private DemoAiTutorConversationEntity toEntity(AiTutorConversation c) {
        DemoAiTutorConversationEntity e = new DemoAiTutorConversationEntity();
        e.setId(c.getId()); e.setClerkUserId(c.getClerkUserId()); e.setTitle(c.getTitle());
        e.setInitialQuery(c.getInitialQuery()); e.setPaperMeta(c.getPaperMeta()); e.setStatus(c.getStatus());
        e.setBaseVersion(c.getBaseVersion()); e.setCreatedAt(c.getCreatedAt()); e.setUpdatedAt(c.getUpdatedAt());
        return e;
    }
    private AiTutorConversation toDomain(DemoAiTutorConversationEntity e) {
        AiTutorConversation c = new AiTutorConversation();
        c.setId(e.getId()); c.setClerkUserId(e.getClerkUserId()); c.setTitle(e.getTitle());
        c.setInitialQuery(e.getInitialQuery()); c.setPaperMeta(e.getPaperMeta()); c.setStatus(e.getStatus());
        c.setBaseVersion(e.getBaseVersion()); c.setCreatedAt(e.getCreatedAt()); c.setUpdatedAt(e.getUpdatedAt());
        return c;
    }
    private DemoAiTutorMessageEntity toEntity(AiTutorMessage m) {
        DemoAiTutorMessageEntity e = new DemoAiTutorMessageEntity();
        e.setId(m.getId()); e.setConversationId(m.getConversationId()); e.setRole(m.getRole());
        e.setMsgType(m.getMsgType()); e.setContentMd(m.getContentMd()); e.setSeq(m.getSeq());
        e.setCreatedAt(m.getCreatedAt());
        return e;
    }
    private AiTutorMessage toDomain(DemoAiTutorMessageEntity e) {
        AiTutorMessage m = new AiTutorMessage();
        m.setId(e.getId()); m.setConversationId(e.getConversationId()); m.setRole(e.getRole());
        m.setMsgType(e.getMsgType()); m.setContentMd(e.getContentMd()); m.setSeq(e.getSeq());
        m.setCreatedAt(e.getCreatedAt());
        return m;
    }
    private DemoAiTutorDocumentEntity toEntity(AiTutorDocument d) {
        DemoAiTutorDocumentEntity e = new DemoAiTutorDocumentEntity();
        e.setId(d.getId()); e.setConversationId(d.getConversationId()); e.setTitle(d.getTitle());
        e.setContentMd(d.getContentMd()); e.setBaseVersion(d.getBaseVersion()); e.setUpdatedBy(d.getUpdatedBy());
        e.setCreatedAt(d.getCreatedAt()); e.setUpdatedAt(d.getUpdatedAt());
        return e;
    }
    private AiTutorDocument toDomain(DemoAiTutorDocumentEntity e) {
        AiTutorDocument d = new AiTutorDocument();
        d.setId(e.getId()); d.setConversationId(e.getConversationId()); d.setTitle(e.getTitle());
        d.setContentMd(e.getContentMd()); d.setBaseVersion(e.getBaseVersion()); d.setUpdatedBy(e.getUpdatedBy());
        d.setCreatedAt(e.getCreatedAt()); d.setUpdatedAt(e.getUpdatedAt());
        return d;
    }
    private DemoAiTutorDocVersionEntity toEntity(AiTutorDocVersion v) {
        DemoAiTutorDocVersionEntity e = new DemoAiTutorDocVersionEntity();
        e.setId(v.getId()); e.setDocumentId(v.getDocumentId()); e.setVersionNo(v.getVersionNo());
        e.setSource(v.getSource()); e.setContentMd(v.getContentMd()); e.setCreatedAt(v.getCreatedAt());
        return e;
    }
    private AiTutorDocVersion toDomain(DemoAiTutorDocVersionEntity e) {
        AiTutorDocVersion v = new AiTutorDocVersion();
        v.setId(e.getId()); v.setDocumentId(e.getDocumentId()); v.setVersionNo(e.getVersionNo());
        v.setSource(e.getSource()); v.setContentMd(e.getContentMd()); v.setCreatedAt(e.getCreatedAt());
        return v;
    }
    private DemoAiTutorEvidenceEntity toEntity(AiTutorEvidence e) {
        DemoAiTutorEvidenceEntity ent = new DemoAiTutorEvidenceEntity();
        ent.setId(e.getId()); ent.setConversationId(e.getConversationId()); ent.setSourceType(e.getSourceType());
        ent.setTitle(e.getTitle()); ent.setUrl(e.getUrl()); ent.setSnippet(e.getSnippet());
        ent.setMetaJson(e.getMetaJson()); ent.setSeqNo(e.getSeqNo()); ent.setConfirmed(e.getConfirmed());
        ent.setCreatedAt(e.getCreatedAt());
        return ent;
    }
    private AiTutorEvidence toDomain(DemoAiTutorEvidenceEntity e) {
        AiTutorEvidence d = new AiTutorEvidence();
        d.setId(e.getId()); d.setConversationId(e.getConversationId()); d.setSourceType(e.getSourceType());
        d.setTitle(e.getTitle()); d.setUrl(e.getUrl()); d.setSnippet(e.getSnippet());
        d.setMetaJson(e.getMetaJson()); d.setSeqNo(e.getSeqNo()); d.setConfirmed(e.getConfirmed());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }
}
