package com.studyagent.service.application;

import com.studyagent.service.domain.user.ClerkClient;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 认证应用服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthApplicationService {
    
    private final ClerkClient clerkClient;
    private final UserRepository userRepository;
    
    /**
     * 获取当前用户信息
     * @param token Clerk token
     * @return 用户信息
     */
    public User getCurrentUser(String token) {
        // 1. 验证 token
        ClerkClient.UserInfo userInfo = clerkClient.verifyToken(token);
        
        // 2. 获取或创建用户
        User user = clerkClient.getOrCreateUser(userInfo.clerkUserId);
        
        // 3. 更新用户信息（如果需要）
        if (userInfo.email != null || userInfo.displayName != null) {
            User updatedUser = User.builder()
                .id(user.getId())
                .clerkUserId(user.getClerkUserId())
                .displayName(userInfo.displayName != null ? userInfo.displayName : user.getDisplayName())
                .locale(user.getLocale())
                .isAdmin(user.getIsAdmin())
                .isActive(user.getIsActive())
                .build();
            user = userRepository.save(updatedUser);
        }
        
        return user;
    }
}

