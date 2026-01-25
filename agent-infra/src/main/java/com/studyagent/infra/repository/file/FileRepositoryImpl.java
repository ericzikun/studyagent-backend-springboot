package com.studyagent.infra.repository.file;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.infra.converter.FileConverter;
import com.studyagent.infra.entity.FileEntity;
import com.studyagent.infra.mapper.FileMapper;
import com.studyagent.service.domain.file.File;
import com.studyagent.service.domain.file.FileId;
import com.studyagent.service.domain.file.FileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

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
}

