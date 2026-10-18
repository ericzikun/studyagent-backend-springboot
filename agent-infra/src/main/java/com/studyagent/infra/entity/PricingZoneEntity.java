package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pricing_zones")
public class PricingZoneEntity extends BaseEntity {
    @TableField("zone_code")
    private String zoneCode;
    private String label;
    @TableField("reference_factor")
    private String referenceFactor;
    @TableField("active_version")
    private String activeVersion;
    @TableField("is_active")
    private Boolean isActive;
}
