package com.studyagent.infra.converter;

import com.studyagent.infra.entity.FileEntity;
import com.studyagent.service.domain.file.File;
import com.studyagent.service.domain.file.FileId;
import org.springframework.stereotype.Component;

/**
 * File Entity 和 Domain Model 转换器
 */
@Component
public class FileConverter {
    
    public File toDomain(FileEntity entity) {
        if (entity == null) {
            return null;
        }
        
        return File.builder()
            .id(FileId.of(entity.getId()))
            .objectId(entity.getObjectId())
            .originalFilename(entity.getOriginalFilename())
            .fileExtension(entity.getFileExtension())
            .contentType(entity.getContentType())
            .fileSize(entity.getFileSize())
            .fileHash(entity.getFileHash())
            .storagePath(entity.getStoragePath())
            .storageType(entity.getStorageType())
            .ossKey(entity.getOssKey())
            .markdownContent(entity.getMarkdownContent())
            .markdownStatus(entity.getMarkdownStatus())
            .markdownError(entity.getMarkdownError())
            .build();
    }
    
    public FileEntity toEntity(File domain) {
        if (domain == null) {
            return null;
        }
        
        FileEntity entity = new FileEntity();
        entity.setId(domain.getId() != null ? domain.getId().getValue() : null);
        entity.setObjectId(domain.getObjectId());
        entity.setOriginalFilename(domain.getOriginalFilename());
        entity.setFileExtension(domain.getFileExtension());
        entity.setContentType(domain.getContentType());
        entity.setFileSize(domain.getFileSize());
        entity.setFileHash(domain.getFileHash());
        entity.setStoragePath(domain.getStoragePath());
        entity.setStorageType(domain.getStorageType());
        entity.setOssKey(domain.getOssKey());
        entity.setMarkdownContent(domain.getMarkdownContent());
        entity.setMarkdownStatus(domain.getMarkdownStatus());
        entity.setMarkdownError(domain.getMarkdownError());
        
        return entity;
    }
}

