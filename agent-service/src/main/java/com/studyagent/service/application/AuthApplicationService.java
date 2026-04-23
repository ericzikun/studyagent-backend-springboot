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
import java.util.Optional;

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
    public AuthenticatedUser getCurrentUser(String token) {
        // 1. 验证 token
        ClerkClient.UserInfo userInfo = clerkClient.verifyToken(token);

        // 2. 获取或创建用户
        User user = clerkClient.getOrCreateUser(userInfo.clerkUserId);

        // 3. 解析邮箱：JWT 优先；库内仍为空时再请求 Clerk API（旧用户回填）
        String resolvedEmail = resolveEmailForPersist(userInfo, user.getEmail());
        boolean displayFromToken = userInfo.displayName != null;
        boolean emailToPersist = hasText(resolvedEmail)
            && !resolvedEmail.equals(Optional.ofNullable(user.getEmail()).orElse(""));

        if (displayFromToken || emailToPersist) {
            User updatedUser = User.builder()
                .id(user.getId())
                .clerkUserId(user.getClerkUserId())
                .email(hasText(resolvedEmail) ? resolvedEmail : user.getEmail())
                .displayName(displayFromToken ? userInfo.displayName : user.getDisplayName())
                .locale(user.getLocale())
                .isAdmin(user.getIsAdmin())
                .isActive(user.getIsActive())
                .createdAt(user.getCreatedAt())
                .build();
            user = userRepository.save(updatedUser);
        }

        // 4. 埋点：用户登录成功
        Map<String, Object> loginProps = new HashMap<>();
        loginProps.put("email", Optional.ofNullable(user.getEmail()).orElse(userInfo.email));
        loginProps.put("display_name", user.getDisplayName());
        loginProps.put("locale", user.getLocale());
        loginProps.put("is_admin", user.getIsAdmin());
        loginProps.put("is_new_user", user.getCreatedAt() != null &&
            user.getCreatedAt().isAfter(java.time.LocalDateTime.now().minusMinutes(1)));
        analyticsService.capture(user.getClerkUserId(), AnalyticsEvents.USER_LOGIN_SUCCESS, loginProps);

        // 5. 设置用户属性
        Map<String, Object> userProps = new HashMap<>();
        userProps.put("email", Optional.ofNullable(user.getEmail()).orElse(userInfo.email));
        userProps.put("name", user.getDisplayName());
        userProps.put("locale", user.getLocale());
        userProps.put("is_admin", user.getIsAdmin());
        analyticsService.setUserProperties(user.getClerkUserId(), userProps);

        return new AuthenticatedUser(user, userInfo);
    }

    private String resolveEmailForPersist(ClerkClient.UserInfo userInfo, String storedEmail) {
        if (hasText(userInfo.email)) {
            return userInfo.email.trim();
        }
        if (!hasText(storedEmail)) {
            String fromApi = clerkClient.getUserEmail(userInfo.clerkUserId);
            if (hasText(fromApi)) {
                return fromApi.trim();
            }
        }
        return null;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}

