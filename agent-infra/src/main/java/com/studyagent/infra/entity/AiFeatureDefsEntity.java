package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI功能定义实体
 * 对应表 ai_feature_defs
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_feature_defs")
public class AiFeatureDefsEntity extends BaseEntity {
    @TableField("feature_code")
    private String featureCode;
    @TableField("feature_name")
    private String featureName;
    @TableField("quota_unit")
    private String quotaUnit;
    @TableField("free_quota_period")
    private String freeQuotaPeriod;
    @TableField("free_quota_amount")
    private Long freeQuotaAmount;
    @TableField("is_active")
    private Boolean isActive;
    @TableField("display_order")
    private Integer displayOrder;
}
