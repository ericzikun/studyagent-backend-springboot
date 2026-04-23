package com.studyagent.infra.converter;

import com.studyagent.infra.entity.UserProfileEntity;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserId;
import org.springframework.stereotype.Component;

/**
 * User Entity 和 Domain Model 转换器
 */
@Component
public class UserConverter {
    
    public User toDomain(UserProfileEntity entity) {
        if (entity == null) {
            return null;
        }
        
        return User.builder()
            .id(UserId.of(entity.getClerkUserId()))
            .clerkUserId(entity.getClerkUserId())
            .email(entity.getEmail())
            .displayName(entity.getDisplayName())
            .locale(entity.getLocale())
            .isAdmin(entity.getIsAdmin())
            .isActive(entity.getIsActive())
            .createdAt(entity.getCreatedAt())
            .build();
    }
    
    public UserProfileEntity toEntity(User domain) {
        if (domain == null) {
            return null;
        }
        
        UserProfileEntity entity = new UserProfileEntity();
        entity.setId(null); // Clerk User ID 是主键
        entity.setClerkUserId(domain.getClerkUserId());
        entity.setEmail(domain.getEmail());
        entity.setDisplayName(domain.getDisplayName());
        entity.setLocale(domain.getLocale());
        entity.setIsAdmin(domain.getIsAdmin());
        entity.setIsActive(domain.getIsActive());
        entity.setCreatedAt(domain.getCreatedAt());
        
        return entity;
    }
}

