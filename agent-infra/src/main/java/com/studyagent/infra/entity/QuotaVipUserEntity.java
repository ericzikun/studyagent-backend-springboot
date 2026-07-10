package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quota_vip_users")
public class QuotaVipUserEntity extends BaseEntity {
    @TableField("clerk_user_id")
    private String clerkUserId;
    private String status;
    private String note;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
}
