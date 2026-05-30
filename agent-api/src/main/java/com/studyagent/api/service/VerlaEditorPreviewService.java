package com.studyagent.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.entity.verla.VerlaEditorPreviewEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
import com.studyagent.infra.mapper.verla.VerlaEditorPreviewMapper;
import com.studyagent.service.application.verla.VerlaConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VerlaEditorPreviewService {

    private final VerlaEditorPreviewMapper previewMapper;
    private final VerlaArtifactMapper artifactMapper;
    private final VerlaConversationService conversationService;

    public void ensureOwnership(String clerkUserId, Long conversationId, String artifactUid) {
        conversationService.getOwned(clerkUserId, conversationId);
        VerlaArtifactEntity artifact = artifactMapper.selectByUid(artifactUid);
        if (artifact == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "artifact");
        }
        if (!conversationId.equals(artifact.getConversationId())) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
    }

    public VerlaEditorPreviewEntity getPreview(Long conversationId, String artifactUid, String editorKind) {
        return previewMapper.selectOne(
                new LambdaQueryWrapper<VerlaEditorPreviewEntity>()
                        .eq(VerlaEditorPreviewEntity::getConversationId, conversationId)
                        .eq(VerlaEditorPreviewEntity::getSourceArtifactUid, artifactUid)
                        .eq(VerlaEditorPreviewEntity::getEditorKind, editorKind)
                        .orderByDesc(VerlaEditorPreviewEntity::getUpdatedAt)
                        .last("LIMIT 1")
        );
    }

    @Transactional
    public VerlaEditorPreviewEntity upsertPreview(
            String clerkUserId,
            Long conversationId,
            String artifactUid,
            String editorKind,
            String previewUrl,
            String attachmentObjectId,
            String contentHash,
            String captureSource,
            Integer width,
            Integer height) {

        VerlaEditorPreviewEntity existing = previewMapper.selectOne(
                new LambdaQueryWrapper<VerlaEditorPreviewEntity>()
                        .eq(VerlaEditorPreviewEntity::getConversationId, conversationId)
                        .eq(VerlaEditorPreviewEntity::getSourceArtifactUid, artifactUid)
                        .eq(VerlaEditorPreviewEntity::getEditorKind, editorKind)
                        .orderByDesc(VerlaEditorPreviewEntity::getUpdatedAt)
                        .last("LIMIT 1")
        );

        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            LambdaUpdateWrapper<VerlaEditorPreviewEntity> update = new LambdaUpdateWrapper<>();
            update.eq(VerlaEditorPreviewEntity::getId, existing.getId())
                    .set(VerlaEditorPreviewEntity::getPreviewUrl, previewUrl)
                    .set(VerlaEditorPreviewEntity::getAttachmentObjectId, attachmentObjectId)
                    .set(VerlaEditorPreviewEntity::getContentHash, contentHash)
                    .set(VerlaEditorPreviewEntity::getCaptureSource, captureSource)
                    .set(VerlaEditorPreviewEntity::getWidth, width)
                    .set(VerlaEditorPreviewEntity::getHeight, height)
                    .set(VerlaEditorPreviewEntity::getUpdatedBy, clerkUserId)
                    .set(VerlaEditorPreviewEntity::getUpdatedAt, now);
            previewMapper.update(null, update);
            existing.setPreviewUrl(previewUrl);
            existing.setAttachmentObjectId(attachmentObjectId);
            existing.setContentHash(contentHash);
            existing.setCaptureSource(captureSource);
            existing.setWidth(width);
            existing.setHeight(height);
            existing.setUpdatedBy(clerkUserId);
            existing.setUpdatedAt(now);
            return existing;
        }

        VerlaEditorPreviewEntity entity = new VerlaEditorPreviewEntity();
        entity.setConversationId(conversationId);
        entity.setSourceArtifactUid(artifactUid);
        entity.setEditorKind(editorKind);
        entity.setPreviewUrl(previewUrl);
        entity.setAttachmentObjectId(attachmentObjectId);
        entity.setContentHash(contentHash);
        entity.setCaptureSource(captureSource);
        entity.setWidth(width);
        entity.setHeight(height);
        entity.setCreatedBy(clerkUserId);
        entity.setUpdatedBy(clerkUserId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        previewMapper.insert(entity);
        return entity;
    }

    public List<VerlaEditorPreviewEntity> listByConversation(Long conversationId) {
        return previewMapper.selectByConversationId(conversationId);
    }

    public List<VerlaEditorPreviewEntity> listByConversationIds(List<Long> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return List.of();
        }
        return previewMapper.selectByConversationIds(conversationIds);
    }
}
