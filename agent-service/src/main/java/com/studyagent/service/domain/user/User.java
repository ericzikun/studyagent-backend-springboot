package com.studyagent.service.domain.user;

import lombok.Builder;
import lombok.Value;

/**
 * 用户领域模型
 */
@Value
@Builder
public class User {
    UserId id;
    String clerkUserId;
    String displayName;
    String locale;
    Boolean isAdmin;
    Boolean isActive;
}

