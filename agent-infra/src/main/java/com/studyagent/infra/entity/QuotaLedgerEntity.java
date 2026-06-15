package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 额度流水实体
 * 对应表 quota_ledger（仅 created_at，无 updated_at）
 */
@Data
@TableName("quota_ledger")
public class QuotaLedgerEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("ledger_no")
    private String ledgerNo;
    @TableField("clerk_user_id")
    private String clerkUserId;
    @TableField("feature_code")
    private String featureCode;
    @TableField("ledger_type")
    private String ledgerType;
    @TableField("amount")
    private Long amount;
    @TableField("source_type")
    private String sourceType;
    @TableField("source_id")
    private String sourceId;
    @TableField("idempotency_key")
    private String idempotencyKey;
    @TableField("subscription_id")
    private String subscriptionId;
    @TableField("invoice_id")
    private String invoiceId;
    @TableField("free_balance_after")
    private Long freeBalanceAfter;
    @TableField("plan_balance_after")
    private Long planBalanceAfter;
    @TableField("addon_balance_after")
    private Long addonBalanceAfter;
    @TableField("paid_balance_after")
    private Long paidBalanceAfter;
    @TableField("biz_context")
    private String bizContext;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
