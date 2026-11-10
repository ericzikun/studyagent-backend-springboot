package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("simulation_lab_pass_products")
public class SimulationPassProductEntity extends BaseEntity {
    @TableField("pass_code")
    private String passCode;
    @TableField("stripe_price_id")
    private String stripePriceId;
    @TableField("amount_cents")
    private Integer amountCents;
    private String currency;
    @TableField("access_days")
    private Integer accessDays;
    @TableField("is_active")
    private Boolean isActive;
}
