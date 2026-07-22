package com.studyagent.service.domain.user;

/**
 * Clerk客户端接口
 */
public interface ClerkClient {
    /**
     * 使用 Clerk 信任密钥验证 session token 的签名和有效期，并返回已认证身份。
     * 实现不得把只解码、未验签的 JWT claims 用作认证依据。
     *
     * @param token Clerk session token，可带 Bearer 前缀
     * @return 由已验证 claims 构造的用户信息
     * @throws IllegalArgumentException token 无效时抛出
     * @throws IllegalStateException 验签配置或基础设施不可用时抛出
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
