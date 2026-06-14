-- V2 user subscription mirror and durable Stripe webhook processing state.

USE studyagent;

CREATE TABLE IF NOT EXISTS user_subscriptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    clerk_user_id VARCHAR(255) NOT NULL,
    tier VARCHAR(16) NOT NULL DEFAULT 'free',
    plan_code VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'free',
    stripe_customer_id VARCHAR(255) NULL,
    stripe_subscription_id VARCHAR(255) NULL,
    stripe_schedule_id VARCHAR(255) NULL,
    current_period_start DATETIME NULL,
    current_period_end DATETIME NULL,
    quota_period_start DATETIME NULL,
    quota_period_end DATETIME NULL,
    cancel_at_period_end TINYINT(1) NOT NULL DEFAULT 0,
    pending_plan_code VARCHAR(64) NULL,
    pending_effective_at DATETIME NULL,
    grace_end_at DATETIME NULL,
    last_synced_at DATETIME NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_subscription_user (clerk_user_id),
    UNIQUE KEY uk_user_subscription_customer (stripe_customer_id),
    UNIQUE KEY uk_user_subscription_subscription (stripe_subscription_id),
    INDEX idx_user_subscription_status (status, current_period_end),
    INDEX idx_user_subscription_pending (pending_plan_code, pending_effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Local mirror of the current Stripe subscription per user';

CREATE TABLE IF NOT EXISTS stripe_webhook_events (
    event_id VARCHAR(255) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    object_id VARCHAR(255) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'received',
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(2000) NULL,
    received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processing_started_at DATETIME NULL,
    processed_at DATETIME NULL,
    INDEX idx_stripe_webhook_status_received (status, received_at),
    INDEX idx_stripe_webhook_object (object_id, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Durable Stripe webhook inbox with retryable processing state';
