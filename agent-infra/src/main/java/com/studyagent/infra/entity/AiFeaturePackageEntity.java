package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI功能充值套餐定义实体
 * 对应表 ai_feature_packages
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_feature_packages")
public class AiFeaturePackageEntity extends BaseEntity {
    @TableField("feature_code")
    private String featureCode;
    @TableField("package_code")
    private String packageCode;
    @TableField("package_name")
    private String packageName;
    @TableField("quota_amount")
    private Long quotaAmount;
    @TableField("price_cents")
    private Integer priceCents;
    @TableField("currency")
    private String currency;
    @TableField("stripe_price_id")
    private String stripePriceId;
    @TableField("stripe_product_id")
    private String stripeProductId;
    @TableField("is_active")
    private Boolean isActive;
    @TableField("display_order")
    private Integer displayOrder;
}
