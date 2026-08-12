package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.common.verla.enums.VerlaAttachmentStatus;
import com.studyagent.infra.entity.verla.VerlaAttachmentEntity;
import com.studyagent.infra.mapper.verla.VerlaAttachmentMapper;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Repository
public class VerlaAttachmentRepositoryImpl
        extends ServiceImpl<VerlaAttachmentMapper, VerlaAttachmentEntity>
        implements VerlaAttachmentRepository {

    @Override
    public VerlaAttachment save(VerlaAttachment attachment) {
        if (attachment == null) {
            throw new IllegalArgumentException("attachment is null");
        }
        LocalDateTime now = LocalDateTime.now();
        VerlaAttachmentEntity entity = toEntity(attachment);
        if (entity.getCreatedAt() == null) entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        if (entity.getStatus() == null) entity.setStatus(VerlaAttachmentStatus.UPLOADED.name());
        this.save(entity);
        attachment.setId(entity.getId());
        attachment.setCreatedAt(entity.getCreatedAt());
        attachment.setUpdatedAt(entity.getUpdatedAt());
        return attachment;
    }

    @Override
    public VerlaAttachment findById(Long id) {
        return toDomain(this.getById(id));
    }

    @Override
    public VerlaAttachment findByObjectId(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            return null;
        }
        return toDomain(this.baseMapper.selectByObjectId(objectId));
    }

    @Override
    public List<VerlaAttachment> findByObjectIds(List<String> objectIds) {
        if (objectIds == null || objectIds.isEmpty()) {
            return Collections.emptyList();
        }
        QueryWrapper<VerlaAttachmentEntity> qw = new QueryWrapper<>();
        qw.in("object_id", objectIds);
        return this.baseMapper.selectList(qw)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<VerlaAttachment> listByConversation(Long conversationId, int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return this.baseMapper.selectByConversation(conversationId, safe)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<VerlaAttachment> listByTurn(Long turnId) {
        return this.baseMapper.selectByTurn(turnId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public Map<Long, List<VerlaAttachment>> listUserUploadsByConversationIds(List<Long> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = conversationIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return this.baseMapper.selectUserUploadsByConversationIds(ids).stream()
                .map(this::toDomain)
                .filter(a -> a.getConversationId() != null)
                .collect(Collectors.groupingBy(VerlaAttachment::getConversationId));
    }

    @Override
    public long countActiveUserUploadsForConversation(Long conversationId, LocalDateTime pendingCutoff) {
        if (conversationId == null) {
            return 0L;
        }
        Long count = this.baseMapper.countActiveUserUploadsForConversation(
                conversationId,
                pendingCutoff == null ? LocalDateTime.MIN : pendingCutoff
        );
        return count == null ? 0L : count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VerlaAttachment softDeleteUserUpload(String clerkUserId, String objectId) {
        if (clerkUserId == null || clerkUserId.isBlank() || objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("clerkUserId and objectId are required");
        }
        VerlaAttachmentEntity existing = this.baseMapper.selectActiveUserUploadForUpdate(clerkUserId, objectId);
        if (existing == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = this.baseMapper.softDeleteById(existing.getId(), now);
        if (rows <= 0) {
            return null;
        }
        existing.setDeletedAt(now);
        existing.setUpdatedAt(now);
        return toDomain(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VerlaAttachment updateParseProgress(VerlaAttachment patch) {
        if (patch == null || patch.getObjectId() == null || patch.getObjectId().isBlank()) {
            throw new IllegalArgumentException("object_id is required for updateParseProgress");
        }
        VerlaAttachmentEntity existing = this.baseMapper.selectByObjectId(patch.getObjectId());
        if (existing == null) {
            throw new IllegalStateException("attachment not found: " + patch.getObjectId());
        }
        VerlaAttachmentStatus existingStatus = safeStatus(existing.getStatus());
        VerlaAttachmentStatus incomingStatus = safeStatus(patch.getStatus());
        if (existingStatus != null && existingStatus.isTerminal()
                && incomingStatus != null && !incomingStatus.isTerminal()) {
            return toDomain(existing);
        }

        if (patch.getStatus() != null)             existing.setStatus(patch.getStatus());
        if (patch.getParseProgress() != null)      existing.setParseProgress(patch.getParseProgress());
        if (patch.getParseError() != null)         existing.setParseError(patch.getParseError());
        if (patch.getSummary() != null)            existing.setSummary(patch.getSummary());
        if (patch.getPrimaryArtifactUid() != null) existing.setPrimaryArtifactUid(patch.getPrimaryArtifactUid());
        if (patch.getMetaJson() != null)           existing.setMetaJson(patch.getMetaJson());
        if (patch.getMarkdownContent() != null)    existing.setMarkdownContent(patch.getMarkdownContent());
        if (patch.getImagesJson() != null)         existing.setImagesJson(patch.getImagesJson());
        if (patch.getTurnId() != null)             existing.setTurnId(patch.getTurnId());
        existing.setUpdatedAt(LocalDateTime.now());

        this.baseMapper.updateById(existing);
        return toDomain(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VerlaAttachment updateByObjectIdSelective(VerlaAttachment patch) {
        if (patch == null || patch.getObjectId() == null || patch.getObjectId().isBlank()) {
            throw new IllegalArgumentException("object_id is required");
        }
        VerlaAttachmentEntity existing = this.baseMapper.selectByObjectId(patch.getObjectId());
        if (existing == null) {
            throw new IllegalStateException("attachment not found: " + patch.getObjectId());
        }
        if (patch.getStorageUri() != null) {
            existing.setStorageUri(patch.getStorageUri());
        }
        if (patch.getOssKey() != null) {
            existing.setOssKey(patch.getOssKey());
        }
        if (patch.getChecksumSha256() != null) {
            existing.setChecksumSha256(patch.getChecksumSha256());
        }
        if (patch.getTurnId() != null) {
            existing.setTurnId(patch.getTurnId());
        }
        if (patch.getSessionId() != null) {
            existing.setSessionId(patch.getSessionId());
        }
        if (patch.getStatus() != null) {
            existing.setStatus(patch.getStatus());
        }
        if (patch.getSizeBytes() != null) {
            existing.setSizeBytes(patch.getSizeBytes());
        }
        if (patch.getMetaJson() != null) {
            existing.setMetaJson(patch.getMetaJson());
        }
        if (patch.getAttachmentOrigin() != null) {
            existing.setAttachmentOrigin(patch.getAttachmentOrigin());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        this.baseMapper.updateById(existing);
        return toDomain(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markStaleUploadedAgentOutputsFailed(LocalDateTime cutoff, int batchSize, String reason) {
        if (cutoff == null || batchSize <= 0) {
            return 0;
        }
        return this.baseMapper.markStaleUploadedAgentOutputsFailed(cutoff, batchSize, reason);
    }

    private VerlaAttachmentStatus safeStatus(String s) {
        if (s == null) return null;
        try { return VerlaAttachmentStatus.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private VerlaAttachment toDomain(VerlaAttachmentEntity e) {
        if (e == null) return null;
        return VerlaAttachment.builder()
                .id(e.getId())
                .objectId(e.getObjectId())
                .conversationId(e.getConversationId())
                .turnId(e.getTurnId())
                .sessionId(e.getSessionId())
                .userId(e.getUserId())
                .filename(e.getFilename())
                .mime(e.getMime())
                .sizeBytes(e.getSizeBytes())
                .storageUri(e.getStorageUri())
                .ossKey(e.getOssKey())
                .checksumSha256(e.getChecksumSha256())
                .status(e.getStatus())
                .parseProgress(e.getParseProgress())
                .parseError(e.getParseError())
                .summary(e.getSummary())
                .primaryArtifactUid(e.getPrimaryArtifactUid())
                .metaJson(e.getMetaJson())
                .attachmentOrigin(e.getAttachmentOrigin())
                .markdownContent(e.getMarkdownContent())
                .imagesJson(e.getImagesJson())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .deletedAt(e.getDeletedAt())
                .build();
    }

    private VerlaAttachmentEntity toEntity(VerlaAttachment d) {
        return new VerlaAttachmentEntity()
                .setId(d.getId())
                .setObjectId(d.getObjectId())
                .setConversationId(d.getConversationId())
                .setTurnId(d.getTurnId())
                .setSessionId(d.getSessionId())
                .setUserId(d.getUserId())
                .setFilename(d.getFilename())
                .setMime(d.getMime())
                .setSizeBytes(d.getSizeBytes())
                .setStorageUri(d.getStorageUri())
                .setOssKey(d.getOssKey())
                .setChecksumSha256(d.getChecksumSha256())
                .setStatus(d.getStatus())
                .setParseProgress(d.getParseProgress())
                .setParseError(d.getParseError())
                .setSummary(d.getSummary())
                .setPrimaryArtifactUid(d.getPrimaryArtifactUid())
                .setMetaJson(d.getMetaJson())
                .setAttachmentOrigin(d.getAttachmentOrigin())
                .setMarkdownContent(d.getMarkdownContent())
                .setImagesJson(d.getImagesJson())
                .setCreatedAt(d.getCreatedAt())
                .setUpdatedAt(d.getUpdatedAt())
                .setDeletedAt(d.getDeletedAt());
    }
}
