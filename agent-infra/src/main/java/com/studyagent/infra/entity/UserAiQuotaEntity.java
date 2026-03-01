package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户AI额度余额实体
 * 对应表 user_ai_quotas
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_ai_quotas")
public class UserAiQuotaEntity extends BaseEntity {
    @TableField("clerk_user_id")
    private String clerkUserId;
    @TableField("feature_code")
    private String featureCode;
    @TableField("free_balance")
    private Long freeBalance;
    @TableField("free_period_start")
    private LocalDateTime freePeriodStart;
    @TableField("free_period_end")
    private LocalDateTime freePeriodEnd;
    @TableField("paid_balance")
    private Long paidBalance;
    @Version
    @TableField("version")
    private Integer version;
}
