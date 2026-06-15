-- Extend the existing V1 recharge order table for V2 subscription and add-on billing.

USE studyagent;

ALTER TABLE recharge_orders
    ADD COLUMN order_type VARCHAR(32) NOT NULL DEFAULT 'legacy_recharge' AFTER order_no,
    ADD COLUMN plan_code VARCHAR(64) NULL AFTER package_code,
    ADD COLUMN addon_code VARCHAR(64) NULL AFTER plan_code,
    ADD COLUMN stripe_invoice_id VARCHAR(255) NULL AFTER stripe_payment_intent_id,
    ADD COLUMN stripe_subscription_id VARCHAR(255) NULL AFTER stripe_invoice_id;

ALTER TABLE recharge_orders
    ADD UNIQUE KEY uk_recharge_stripe_invoice (stripe_invoice_id),
    ADD INDEX idx_recharge_subscription (stripe_subscription_id, created_at),
    ADD INDEX idx_recharge_order_type_status (order_type, status, created_at);
