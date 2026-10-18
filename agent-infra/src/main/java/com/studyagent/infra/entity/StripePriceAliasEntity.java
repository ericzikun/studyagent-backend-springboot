package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stripe_price_aliases")
public class StripePriceAliasEntity extends BaseEntity {
    @TableField("stripe_price_id")
    private String stripePriceId;
    @TableField("sku_code")
    private String skuCode;
    @TableField("sku_kind")
    private String skuKind;
    private String note;
}
