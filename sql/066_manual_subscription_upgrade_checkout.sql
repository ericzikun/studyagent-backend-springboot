USE studyagent;

ALTER TABLE recharge_orders
    ADD COLUMN target_plan_code VARCHAR(64) NULL AFTER plan_code,
    ADD COLUMN upgrade_charge_type VARCHAR(32) NULL AFTER target_plan_code,
    ADD COLUMN quoted_amount_cents INT NULL AFTER price_cents,
    ADD COLUMN upgrade_effective_at DATETIME NULL AFTER paid_at,
    ADD COLUMN switch_attempts INT NOT NULL DEFAULT 0 AFTER upgrade_effective_at,
    ADD COLUMN biz_context JSON NULL AFTER switch_attempts,
    ADD INDEX idx_recharge_upgrade_user_status (clerk_user_id, order_type, status, created_at);

ALTER TABLE user_subscriptions
    ADD COLUMN pending_upgrade_order_no VARCHAR(64) NULL AFTER pending_effective_at,
    ADD COLUMN pending_upgrade_expires_at DATETIME NULL AFTER pending_upgrade_order_no,
    ADD INDEX idx_user_subscription_pending_upgrade (pending_upgrade_order_no, pending_upgrade_expires_at);
