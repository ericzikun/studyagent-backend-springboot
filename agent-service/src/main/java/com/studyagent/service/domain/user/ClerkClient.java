package com.studyagent.service.domain.user;

/**
 * Clerk客户端接口
 */
public interface ClerkClient {
    /**
     * 验证 Clerk token
     */
    UserInfo verifyToken(String token);
    
    /**
     * 获取或创建用户
     */
    User getOrCreateUser(String clerkUserId);
    
    /**
     * 根据 Clerk 用户 ID 获取用户邮箱
     * 调用 Clerk Backend API，带超时容错，失败返回 null
     */
    String getUserEmail(String clerkUserId);
    
    /**
     * Clerk用户信息
     */
    class UserInfo {
        public String clerkUserId;
        public String email;
        public String displayName;
        public String avatarUrl;
        public Boolean emailVerified;
    }
}

