package com.studyagent.service.application;

import com.studyagent.common.analytics.AnalyticsEvents;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.service.domain.user.ClerkClient;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证应用服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthApplicationService {

    private final ClerkClient clerkClient;
    private final UserRepository userRepository;
    private final AnalyticsService analyticsService;

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
                .createdAt(user.getCreatedAt())
                .build();
            user = userRepository.save(updatedUser);
        }

        // 4. 埋点：用户登录成功
        Map<String, Object> loginProps = new HashMap<>();
        loginProps.put("email", userInfo.email);
        loginProps.put("display_name", user.getDisplayName());
        loginProps.put("locale", user.getLocale());
        loginProps.put("is_admin", user.getIsAdmin());
        loginProps.put("is_new_user", user.getCreatedAt() != null &&
            user.getCreatedAt().isAfter(java.time.LocalDateTime.now().minusMinutes(1)));
        analyticsService.capture(user.getClerkUserId(), AnalyticsEvents.USER_LOGIN_SUCCESS, loginProps);

        // 5. 设置用户属性
        Map<String, Object> userProps = new HashMap<>();
        userProps.put("email", userInfo.email);
        userProps.put("name", user.getDisplayName());
        userProps.put("locale", user.getLocale());
        userProps.put("is_admin", user.getIsAdmin());
        analyticsService.setUserProperties(user.getClerkUserId(), userProps);

        return user;
    }
}

