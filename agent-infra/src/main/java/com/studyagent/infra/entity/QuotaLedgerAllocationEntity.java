package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quota_ledger_allocations")
public class QuotaLedgerAllocationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("quota_ledger_id")
    private Long quotaLedgerId;
    @TableField("pool_type")
    private String poolType;
    @TableField("grant_id")
    private Long grantId;
    private Long amount;
    @TableField("source_period_end")
    private LocalDateTime sourcePeriodEnd;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
