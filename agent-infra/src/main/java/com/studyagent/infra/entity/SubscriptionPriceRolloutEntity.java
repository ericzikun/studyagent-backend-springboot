package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("subscription_price_rollouts")
public class SubscriptionPriceRolloutEntity extends BaseEntity {
    @TableField("batch_id")
    private String batchId;
    @TableField("contract_kind")
    private String contractKind;
    @TableField("clerk_user_id")
    private String clerkUserId;
    @TableField("stripe_subscription_id")
    private String stripeSubscriptionId;
    @TableField("zone_code")
    private String zoneCode;
    private String currency;
    @TableField("source_price_id")
    private String sourcePriceId;
    @TableField("source_version")
    private String sourceVersion;
    @TableField("target_price_id")
    private String targetPriceId;
    @TableField("target_version")
    private String targetVersion;
    @TableField("effective_at")
    private LocalDateTime effectiveAt;
    private String status;
    @TableField("status_reason")
    private String statusReason;
    /** JSON 文本：写入前合同快照 */
    @TableField("snapshot_json")
    private String snapshotJson;
    @TableField("idempotency_key")
    private String idempotencyKey;
}
