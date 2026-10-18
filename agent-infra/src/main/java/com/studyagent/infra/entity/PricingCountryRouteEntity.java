package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pricing_country_routes")
public class PricingCountryRouteEntity extends BaseEntity {
    @TableField("country_code")
    private String countryCode;
    @TableField("zone_code")
    private String zoneCode;
    private String currency;
    private String label;
    @TableField("is_enabled")
    private Boolean isEnabled;
}
