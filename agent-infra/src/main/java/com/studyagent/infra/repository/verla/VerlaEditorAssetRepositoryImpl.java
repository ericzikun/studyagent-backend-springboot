package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infra.entity.verla.VerlaEditorAssetEntity;
import com.studyagent.infra.mapper.verla.VerlaEditorAssetMapper;
import com.studyagent.service.domain.verla.VerlaEditorAsset;
import com.studyagent.service.domain.verla.repo.VerlaEditorAssetRepository;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Repository
public class VerlaEditorAssetRepositoryImpl
        extends ServiceImpl<VerlaEditorAssetMapper, VerlaEditorAssetEntity>
        implements VerlaEditorAssetRepository {

    @Override
    public VerlaEditorAsset save(VerlaEditorAsset asset) {
        VerlaEditorAssetEntity entity = toEntity(asset);
        baseMapper.insert(entity);
        return toDomain(entity);
    }

    @Override
    public VerlaEditorAsset findByAssetId(String assetId) {
        VerlaEditorAssetEntity entity = baseMapper.selectByAssetId(assetId);
        return entity == null ? null : toDomain(entity);
    }

    @Override
    public VerlaEditorAsset updateByAssetIdSelective(VerlaEditorAsset patch) {
        if (patch == null || !StringUtils.hasText(patch.getAssetId())) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "assetId required for update");
        }
        LambdaUpdateWrapper<VerlaEditorAssetEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(VerlaEditorAssetEntity::getAssetId, patch.getAssetId());

        if (patch.getStorageUri() != null) {
            wrapper.set(VerlaEditorAssetEntity::getStorageUri, patch.getStorageUri());
        }
        if (patch.getOssKey() != null) {
            wrapper.set(VerlaEditorAssetEntity::getOssKey, patch.getOssKey());
        }
        if (patch.getChecksumSha256() != null) {
            wrapper.set(VerlaEditorAssetEntity::getChecksumSha256, patch.getChecksumSha256());
        }
        if (patch.getStatus() != null) {
            wrapper.set(VerlaEditorAssetEntity::getStatus, patch.getStatus());
        }
        if (patch.getSizeBytes() != null) {
            wrapper.set(VerlaEditorAssetEntity::getSizeBytes, patch.getSizeBytes());
        }
        wrapper.set(VerlaEditorAssetEntity::getUpdatedAt, LocalDateTime.now());

        baseMapper.update(null, wrapper);
        return findByAssetId(patch.getAssetId());
    }

    private VerlaEditorAssetEntity toEntity(VerlaEditorAsset domain) {
        VerlaEditorAssetEntity entity = new VerlaEditorAssetEntity();
        entity.setId(domain.getId());
        entity.setAssetId(domain.getAssetId());
        entity.setConversationId(domain.getConversationId());
        entity.setArtifactUid(domain.getArtifactUid());
        entity.setEditorKind(domain.getEditorKind());
        entity.setAssetRole(domain.getAssetRole());
        entity.setUserId(domain.getUserId());
        entity.setFilename(domain.getFilename());
        entity.setMime(domain.getMime());
        entity.setSizeBytes(domain.getSizeBytes());
        entity.setStorageUri(domain.getStorageUri());
        entity.setOssKey(domain.getOssKey());
        entity.setChecksumSha256(domain.getChecksumSha256());
        entity.setStatus(domain.getStatus());
        entity.setMetaJson(domain.getMetaJson());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    private VerlaEditorAsset toDomain(VerlaEditorAssetEntity entity) {
        return VerlaEditorAsset.builder()
                .id(entity.getId())
                .assetId(entity.getAssetId())
                .conversationId(entity.getConversationId())
                .artifactUid(entity.getArtifactUid())
                .editorKind(entity.getEditorKind())
                .assetRole(entity.getAssetRole())
                .userId(entity.getUserId())
                .filename(entity.getFilename())
                .mime(entity.getMime())
                .sizeBytes(entity.getSizeBytes())
                .storageUri(entity.getStorageUri())
                .ossKey(entity.getOssKey())
                .checksumSha256(entity.getChecksumSha256())
                .status(entity.getStatus())
                .metaJson(entity.getMetaJson())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
