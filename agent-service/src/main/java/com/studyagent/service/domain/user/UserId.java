package com.studyagent.service.domain.user;

import lombok.Value;

/**
 * 用户ID值对象
 */
@Value
public class UserId {
    String value; // Clerk User ID
    
    public static UserId of(String value) {
        return new UserId(value);
    }
}

