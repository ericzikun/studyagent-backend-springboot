package com.studyagent.service.domain.user;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 用户领域模型
 */
@Value
@Builder
public class User {
    UserId id;
    String clerkUserId;
    /** 与 Clerk 同步的邮箱，库内可空（旧数据登录时回填） */
    String email;
    String displayName;
    String locale;
    Boolean isAdmin;
    Boolean isActive;
    LocalDateTime createdAt;
}

