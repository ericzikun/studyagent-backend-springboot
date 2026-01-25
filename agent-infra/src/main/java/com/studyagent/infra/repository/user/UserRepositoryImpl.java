package com.studyagent.infra.repository.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.infra.converter.UserConverter;
import com.studyagent.infra.entity.UserProfileEntity;
import com.studyagent.infra.mapper.UserMapper;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserId;
import com.studyagent.service.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 用户Repository实现
 */
@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {
    
    private final UserMapper userMapper;
    private final UserConverter converter;
    
    @Override
    public Optional<User> findById(UserId id) {
        return findByClerkUserId(id.getValue());
    }
    
    @Override
    public Optional<User> findByClerkUserId(String clerkUserId) {
        UserProfileEntity entity = userMapper.selectOne(
            new LambdaQueryWrapper<UserProfileEntity>()
                .eq(UserProfileEntity::getClerkUserId, clerkUserId)
        );
        return Optional.ofNullable(converter.toDomain(entity));
    }
    
    @Override
    public User save(User user) {
        UserProfileEntity entity = converter.toEntity(user);
        
        Optional<User> existing = findByClerkUserId(user.getClerkUserId());
        if (existing.isPresent()) {
            // 更新 - 需要先查询到实体ID
            UserProfileEntity existingEntity = userMapper.selectOne(
                new LambdaQueryWrapper<UserProfileEntity>()
                    .eq(UserProfileEntity::getClerkUserId, user.getClerkUserId())
            );
            if (existingEntity != null) {
                entity.setId(existingEntity.getId());
                entity.setUpdatedAt(LocalDateTime.now());
                userMapper.updateById(entity);
            }
        } else {
            // 新建
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            userMapper.insert(entity);
        }
        
        return converter.toDomain(entity);
    }
    
    @Override
    public void delete(UserId id) {
        userMapper.delete(
            new LambdaQueryWrapper<UserProfileEntity>()
                .eq(UserProfileEntity::getClerkUserId, id.getValue())
        );
    }
}

