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
    private String displayName;
    private String locale;
    /** 国家/地区（运维画像，可为空） */
    private String country;
    private Boolean isAdmin;
    private Boolean isActive;
}

