package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class VerlaArtifactRepositoryImpl
        extends ServiceImpl<VerlaArtifactMapper, VerlaArtifactEntity>
        implements VerlaArtifactRepository {

    @Override
    public VerlaArtifact findById(Long id) {
        return toDomain(this.getById(id));
    }

    @Override
    public VerlaArtifact findByUid(String artifactUid) {
        if (artifactUid == null || artifactUid.isBlank()) {
            return null;
        }
        return toDomain(this.baseMapper.selectByUid(artifactUid));
    }

    @Override
    public List<VerlaArtifact> findByConversation(Long conversationId) {
        return this.baseMapper.selectByConversation(conversationId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<VerlaArtifact> findBySession(Long sessionId) {
        return this.baseMapper.selectBySession(sessionId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<VerlaArtifact> findByUids(List<String> artifactUids) {
        if (artifactUids == null || artifactUids.isEmpty()) {
            return Collections.emptyList();
        }
        QueryWrapper<VerlaArtifactEntity> qw = new QueryWrapper<>();
        qw.in("artifact_uid", artifactUids);
        return this.baseMapper.selectList(qw)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VerlaArtifact upsertByUid(VerlaArtifact patch) {
        if (patch == null || patch.getArtifactUid() == null || patch.getArtifactUid().isBlank()) {
            throw new IllegalArgumentException("artifact_uid is required for upsertByUid");
        }
        VerlaArtifactEntity existing = this.baseMapper.selectByUid(patch.getArtifactUid());
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            VerlaArtifactEntity entity = new VerlaArtifactEntity()
                    .setArtifactUid(patch.getArtifactUid())
                    .setConversationId(patch.getConversationId())
                    .setTurnId(patch.getTurnId())
                    .setSessionId(patch.getSessionId())
                    .setSourceMessageId(patch.getSourceMessageId())
                    .setSourceObjectId(patch.getSourceObjectId())
                    .setKind(patch.getKind())
                    .setMime(patch.getMime())
                    .setSummary(patch.getSummary())
                    .setContentRef(patch.getContentRef())
                    .setBodyOrRef(patch.getBodyOrRef())
                    .setStatus(patch.getStatus() != null ? patch.getStatus() : "READY")
                    .setSizeBytes(patch.getSizeBytes())
                    .setVersion(patch.getVersion() != null ? patch.getVersion() : 1)
                    .setMetaJson(patch.getMetaJson())
                    .setUpdatedAt(now);
            this.baseMapper.insert(entity);
            return toDomain(entity);
        }

        // 高 version 覆盖低 version；同 version 也允许刷新（幂等）。
        Integer incoming = patch.getVersion() != null ? patch.getVersion() : existing.getVersion();
        if (incoming != null && existing.getVersion() != null && incoming < existing.getVersion()) {
            // 旧消息晚到，丢弃覆盖。
            return toDomain(existing);
        }

        if (patch.getKind() != null)            existing.setKind(patch.getKind());
        if (patch.getMime() != null)            existing.setMime(patch.getMime());
        if (patch.getSummary() != null)         existing.setSummary(patch.getSummary());
        if (patch.getContentRef() != null)      existing.setContentRef(patch.getContentRef());
        if (patch.getBodyOrRef() != null)       existing.setBodyOrRef(patch.getBodyOrRef());
        if (patch.getStatus() != null)          existing.setStatus(patch.getStatus());
        if (patch.getSizeBytes() != null)       existing.setSizeBytes(patch.getSizeBytes());
        if (patch.getSourceMessageId() != null) existing.setSourceMessageId(patch.getSourceMessageId());
        if (patch.getSourceObjectId() != null)  existing.setSourceObjectId(patch.getSourceObjectId());
        if (patch.getMetaJson() != null)        existing.setMetaJson(patch.getMetaJson());
        if (incoming != null)                   existing.setVersion(incoming);
        existing.setUpdatedAt(now);

        this.baseMapper.updateById(existing);
        return toDomain(existing);
    }

    private VerlaArtifact toDomain(VerlaArtifactEntity e) {
        if (e == null) {
            return null;
        }
        return VerlaArtifact.builder()
                .id(e.getId())
                .artifactUid(e.getArtifactUid())
                .conversationId(e.getConversationId())
                .turnId(e.getTurnId())
                .sessionId(e.getSessionId())
                .sourceMessageId(e.getSourceMessageId())
                .sourceObjectId(e.getSourceObjectId())
                .kind(e.getKind())
                .mime(e.getMime())
                .summary(e.getSummary())
                .contentRef(e.getContentRef())
                .bodyOrRef(e.getBodyOrRef())
                .status(e.getStatus())
                .sizeBytes(e.getSizeBytes())
                .version(e.getVersion())
                .metaJson(e.getMetaJson())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
