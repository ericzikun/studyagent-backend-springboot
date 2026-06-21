-- V2 quota model expansion: add plan pool fields and richer ledger snapshots.

USE studyagent;

ALTER TABLE user_ai_quotas
    ADD COLUMN plan_balance BIGINT NOT NULL DEFAULT 0 COMMENT 'Current subscription plan balance for this feature' AFTER free_period_end,
    ADD COLUMN plan_period_start DATETIME NULL COMMENT 'Current plan quota period start' AFTER plan_balance,
    ADD COLUMN plan_period_end DATETIME NULL COMMENT 'Current plan quota period end' AFTER plan_period_start;

ALTER TABLE quota_ledger
    ADD COLUMN idempotency_key VARCHAR(255) NULL COMMENT 'Business idempotency key for grants / clears / refunds' AFTER source_id,
    ADD COLUMN subscription_id VARCHAR(255) NULL COMMENT 'Stripe subscription id for subscription-driven ledger entries' AFTER idempotency_key,
    ADD COLUMN invoice_id VARCHAR(255) NULL COMMENT 'Stripe invoice id for invoice-driven ledger entries' AFTER subscription_id,
    ADD COLUMN plan_balance_after BIGINT NULL COMMENT 'Plan pool balance snapshot after this ledger row' AFTER free_balance_after,
    ADD COLUMN addon_balance_after BIGINT NULL COMMENT 'Aggregated add-on pool balance snapshot after this ledger row' AFTER plan_balance_after;

CREATE UNIQUE INDEX uk_quota_ledger_feature_type_idempotency
    ON quota_ledger (feature_code, ledger_type, idempotency_key);

UPDATE ai_feature_defs
SET quota_unit = 'count',
    free_quota_amount = 1
WHERE feature_code IN ('task_create', 'ai_detection', 'humanizer');
