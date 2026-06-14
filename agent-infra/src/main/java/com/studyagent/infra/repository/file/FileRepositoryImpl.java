package com.studyagent.infra.repository.file;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyagent.infra.converter.FileConverter;
import com.studyagent.infra.entity.FileEntity;
import com.studyagent.infra.mapper.FileMapper;
import com.studyagent.service.domain.file.File;
import com.studyagent.service.domain.file.FileId;
import com.studyagent.service.domain.file.FileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 文件Repository实现
 */
@Repository
@RequiredArgsConstructor
public class FileRepositoryImpl implements FileRepository {
    
    private final FileMapper fileMapper;
    private final FileConverter converter;
    
    @Override
    public Optional<File> findById(FileId id) {
        FileEntity entity = fileMapper.selectById(id.getValue());
        return Optional.ofNullable(converter.toDomain(entity));
    }
    
    @Override
    public Optional<File> findByObjectId(String objectId) {
        FileEntity entity = fileMapper.selectOne(
            new LambdaQueryWrapper<FileEntity>()
                .eq(FileEntity::getObjectId, objectId)
        );
        return Optional.ofNullable(converter.toDomain(entity));
    }

    @Override
    public Map<String, File> findByObjectIds(List<String> objectIds) {
        if (objectIds == null || objectIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = objectIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<FileEntity> entities = fileMapper.selectList(
                new LambdaQueryWrapper<FileEntity>().in(FileEntity::getObjectId, ids));
        Map<String, File> result = new LinkedHashMap<>();
        for (FileEntity entity : entities) {
            if (entity == null || entity.getObjectId() == null) {
                continue;
            }
            result.putIfAbsent(entity.getObjectId(), converter.toDomain(entity));
        }
        return result;
    }
    
    @Override
    public File save(File file) {
        FileEntity entity = converter.toEntity(file);
        
        if (entity.getId() == null) {
            // 新建
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            fileMapper.insert(entity);
        } else {
            // 更新
            entity.setUpdatedAt(LocalDateTime.now());
            fileMapper.updateById(entity);
        }
        
        return converter.toDomain(entity);
    }
    
    @Override
    public void delete(FileId id) {
        fileMapper.deleteById(id.getValue());
    }
    
    @Override
    public boolean updateOssKey(String objectId, String ossKey) {
        int rows = fileMapper.update(null, 
            new LambdaUpdateWrapper<FileEntity>()
                .eq(FileEntity::getObjectId, objectId)
                .set(FileEntity::getOssKey, ossKey)
                .set(FileEntity::getUpdatedAt, LocalDateTime.now())
        );
        return rows > 0;
    }
}

