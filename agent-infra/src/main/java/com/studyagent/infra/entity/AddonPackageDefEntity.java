package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("addon_package_defs")
public class AddonPackageDefEntity extends BaseEntity {
    @TableField("addon_code")
    private String addonCode;
    @TableField("feature_code")
    private String featureCode;
    @TableField("stripe_product_id")
    private String stripeProductId;
    @TableField("stripe_price_id")
    private String stripePriceId;
    @TableField("quota_amount")
    private Long quotaAmount;
    @TableField("validity_months")
    private Integer validityMonths;
    @TableField("price_cents")
    private Integer priceCents;
    private String currency;
    @TableField("config_version")
    private Integer configVersion;
    @TableField("is_active")
    private Boolean isActive;
    @TableField("display_order")
    private Integer displayOrder;
}
