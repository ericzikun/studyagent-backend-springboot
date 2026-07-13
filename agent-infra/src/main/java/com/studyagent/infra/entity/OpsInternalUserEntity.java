package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Ops console internal team users ({@code ops_internal_users}).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_internal_users")
public class OpsInternalUserEntity extends BaseEntity {
    @TableField("clerk_user_id")
    private String clerkUserId;
    private String status;
    private String note;
}
