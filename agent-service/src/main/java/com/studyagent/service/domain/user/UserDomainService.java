package com.studyagent.service.domain.user;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户领域服务
 */
@Slf4j
@Service
public class UserDomainService {
    
    /**
     * 验证用户是否可以访问任务
     */
    public boolean canAccessTask(User user, String taskClerkUserId) {
        if (user == null) {
            // 未登录用户只能访问管理员创建的历史任务（clerk_user_id IS NULL）
            return taskClerkUserId == null;
        }
        
        // 管理员可以访问所有任务
        if (Boolean.TRUE.equals(user.getIsAdmin())) {
            return true;
        }
        
        // 普通用户只能访问自己的任务
        return user.getClerkUserId().equals(taskClerkUserId);
    }
    
    /**
     * 验证用户是否是管理员
     */
    public boolean isAdmin(User user) {
        return user != null && Boolean.TRUE.equals(user.getIsAdmin());
    }
}

