package com.studyagent.service.application;

import com.studyagent.service.domain.user.ClerkClient;
import com.studyagent.service.domain.user.User;

/**
 * 登录态：落库用户 + 本次 Token 解析的 Clerk 信息（如邮箱验证状态）
 */
public record AuthenticatedUser(User user, ClerkClient.UserInfo tokenInfo) {
}
