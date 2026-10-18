package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 不可变 Price 注册表行。stripePriceId 回填后不得改指向；新版本一律插入新行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pricing_zone_prices")
public class PricingZonePriceEntity extends BaseEntity {
    @TableField("zone_code")
    private String zoneCode;
    @TableField("price_version")
    private String priceVersion;
    @TableField("sku_code")
    private String skuCode;
    @TableField("sku_kind")
    private String skuKind;
    @TableField("stripe_price_id")
    private String stripePriceId;
    @TableField("stripe_product_id")
    private String stripeProductId;
    @TableField("price_type")
    private String priceType;
    @TableField("billing_interval")
    private String billingInterval;
    @TableField("interval_count")
    private Integer intervalCount;
    @TableField("usd_default_amount_minor")
    private Integer usdDefaultAmountMinor;
    /** JSON 文本：{"eur":{"amountMinor":1399}}，预检核对基准 */
    @TableField("currency_amounts")
    private String currencyAmounts;
    @TableField("renewal_sku_code")
    private String renewalSkuCode;
    @TableField("is_active")
    private Boolean isActive;
}
