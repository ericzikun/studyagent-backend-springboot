-- Subscription payment recovery. No historical backfill or remote actions.
USE studyagent;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS subscription_payment_recoveries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    clerk_user_id VARCHAR(128) NOT NULL,
    stripe_livemode TINYINT(1) NOT NULL,
    stripe_customer_id VARCHAR(128) NOT NULL,
    stripe_subscription_id VARCHAR(128) NOT NULL,
    stripe_invoice_id VARCHAR(128) NOT NULL,
    owner_recovery_id BIGINT NULL,
    stripe_invoice_status VARCHAR(24) NOT NULL,
    entitlement_invoice_id VARCHAR(128) NULL,
    plan_code VARCHAR(64) NOT NULL,
    display_tier VARCHAR(16) NOT NULL,
    billing_interval VARCHAR(8) NOT NULL,
    interval_count INT NOT NULL DEFAULT 1,
    contract_snapshot JSON NOT NULL,
    currency CHAR(3) NOT NULL,
    amount_due_minor BIGINT NOT NULL,
    amount_paid_minor BIGINT NOT NULL DEFAULT 0,
    amount_remaining_minor BIGINT NOT NULL,
    first_failed_at DATETIME(3) NOT NULL,
    deadline_at DATETIME(3) NOT NULL,
    status VARCHAR(24) NOT NULL,
    latest_decline_code VARCHAR(128) NULL,
    latest_advice_code VARCHAR(128) NULL,
    failure_evidence JSON NULL,
    last_stripe_event_created_at BIGINT NULL,
    last_stripe_event_id VARCHAR(128) NULL,
    protection_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    paid_at DATETIME(3) NULL,
    payment_evidence JSON NULL,
    entitlement_start_at DATETIME(3) NULL,
    entitlement_end_at DATETIME(3) NULL,
    quota_period_end_at DATETIME(3) NULL,
    grant_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
    anchor_sync_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
    technical_invoice_ids JSON NULL,
    close_reason VARCHAR(64) NULL,
    refunded_amount_minor BIGINT NOT NULL DEFAULT 0,
    refund_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
    dispute_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
    last_dispute_id VARCHAR(128) NULL,
    account_closed_at DATETIME(3) NULL,
    review_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
    review_reason VARCHAR(128) NULL,
    review_assignee VARCHAR(128) NULL,
    review_entered_at DATETIME(3) NULL,
    review_due_at DATETIME(3) NULL,
    review_last_progress_at DATETIME(3) NULL,
    review_next_followup_at DATETIME(3) NULL,
    next_reconcile_at DATETIME(3) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_recovery_invoice (stripe_invoice_id),
    KEY idx_recovery_subscription (stripe_subscription_id, status),
    KEY idx_recovery_deadline (status, deadline_at, id),
    KEY idx_recovery_reconcile (next_reconcile_at, id),
    KEY idx_recovery_review (review_state, review_due_at, id),
    KEY idx_recovery_owner (owner_recovery_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS subscription_payment_recovery_actions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recovery_id BIGINT NOT NULL,
    action_type VARCHAR(40) NOT NULL,
    action_key VARCHAR(255) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    retry_slot INT NULL,
    scheduled_at DATETIME(3) NOT NULL,
    next_run_at DATETIME(3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'READY',
    payload_json JSON NOT NULL,
    provider_idempotency_key VARCHAR(255) NULL,
    first_dispatched_at DATETIME(3) NULL,
    transport_attempts INT NOT NULL DEFAULT 0,
    lease_token VARCHAR(64) NULL,
    lease_until DATETIME(3) NULL,
    provider_request_id VARCHAR(255) NULL,
    payment_intent_id VARCHAR(128) NULL,
    charge_id VARCHAR(128) NULL,
    provider_message_id VARCHAR(255) NULL,
    result_json JSON NULL,
    skip_reason VARCHAR(64) NULL,
    last_error VARCHAR(1000) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_recovery_action_key (action_key),
    UNIQUE KEY uk_recovery_retry_slot (recovery_id, retry_slot),
    KEY idx_recovery_action_due (status, next_run_at, id),
    KEY idx_recovery_action_target (target_id, action_type, status),
    KEY idx_recovery_action_owner (recovery_id, status),
    KEY idx_recovery_action_kind_status (action_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS subscription_payment_recovery_replacements (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recovery_id BIGINT NOT NULL,
    clerk_user_id VARCHAR(128) NOT NULL,
    old_subscription_id VARCHAR(128) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    target_plan_code VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    active_subscription_key VARCHAR(128) NULL,
    checkout_session_id VARCHAR(128) NULL,
    new_subscription_id VARCHAR(128) NULL,
    paused_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NULL,
    closed_at DATETIME(3) NULL,
    unknown_since DATETIME(3) NULL,
    quote_snapshot JSON NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_replacement_order (order_no),
    UNIQUE KEY uk_replacement_active (active_subscription_key),
    UNIQUE KEY uk_replacement_checkout (checkout_session_id),
    KEY idx_replacement_reconcile (state, expires_at, id),
    KEY idx_replacement_old_subscription (old_subscription_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

SET @recovery_add_column = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'user_subscriptions'
          AND column_name = 'payment_recovery_id'
    ),
    'SELECT 1',
    'ALTER TABLE user_subscriptions ADD COLUMN payment_recovery_id BIGINT NULL, ADD INDEX idx_subscription_recovery (payment_recovery_id)'
);
PREPARE recovery_stmt FROM @recovery_add_column;
EXECUTE recovery_stmt;
DEALLOCATE PREPARE recovery_stmt;
