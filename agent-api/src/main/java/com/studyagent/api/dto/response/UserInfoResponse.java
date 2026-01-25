package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户信息响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoResponse {
    private String uid;
    private String email;
    private String displayName;
    private String avatarUrl;
    private String locale;
    private Boolean isAdmin;
    private Boolean emailVerified;
    private String createdAt;
    private String lastLoginAt;
}

