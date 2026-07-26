USE studyagent;

-- Preserve the exact add-on validity sold at Checkout creation time. Fulfillment
-- must not depend on mutable catalog rows.
ALTER TABLE recharge_orders
    ADD COLUMN validity_months_snapshot INT NULL AFTER quota_amount;

ALTER TABLE stripe_webhook_events
    ADD COLUMN payload_json LONGTEXT NULL AFTER last_error,
    ADD COLUMN next_retry_at DATETIME NULL AFTER payload_json,
    ADD COLUMN dead_lettered_at DATETIME NULL AFTER next_retry_at,
    ADD INDEX idx_stripe_webhook_retry (status, next_retry_at);

ALTER TABLE user_subscriptions
    ADD COLUMN last_stripe_event_created_at BIGINT NULL AFTER last_synced_at,
    ADD COLUMN last_stripe_event_id VARCHAR(255) NULL AFTER last_stripe_event_created_at;

ALTER TABLE user_addon_grants
    ADD COLUMN reversed_amount BIGINT NOT NULL DEFAULT 0 AFTER remaining_amount,
    ADD COLUMN quota_debt_amount BIGINT NOT NULL DEFAULT 0 AFTER reversed_amount,
    ADD COLUMN pre_dispute_status VARCHAR(16) NULL AFTER quota_debt_amount;
