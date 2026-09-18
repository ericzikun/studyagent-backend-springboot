package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("study_pass_products")
public class StudyPassProductEntity extends BaseEntity {
    @TableField("pass_code")
    private String passCode;
    @TableField("stripe_product_id")
    private String stripeProductId;
    @TableField("stripe_price_id")
    private String stripePriceId;
    @TableField("price_cents")
    private Integer priceCents;
    private String currency;
    @TableField("validity_days")
    private Integer validityDays;
    @TableField("config_version")
    private Integer configVersion;
    @TableField("is_active")
    private Boolean isActive;
    @TableField("display_order")
    private Integer displayOrder;
}
