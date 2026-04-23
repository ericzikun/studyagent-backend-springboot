package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_profiles")
public class UserProfileEntity extends BaseEntity {
    private String clerkUserId;
    private String email;
    private String displayName;
    private String locale;
    private Boolean isAdmin;
    private Boolean isActive;
}

