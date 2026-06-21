-- V2 add-on grants: explicit per-purchase balances with pause/resume lifecycle.

USE studyagent;

CREATE TABLE IF NOT EXISTS user_addon_grants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    clerk_user_id VARCHAR(255) NOT NULL,
    feature_code VARCHAR(64) NOT NULL,
    grant_type VARCHAR(32) NOT NULL COMMENT 'addon / compensation / legacy_migration / legacy_migration_refund',
    addon_code VARCHAR(64) NULL,
    status VARCHAR(16) NOT NULL COMMENT 'active / paused / expired / depleted / revoked',
    initial_amount BIGINT NOT NULL,
    remaining_amount BIGINT NOT NULL,
    stripe_session_id VARCHAR(255) NULL,
    stripe_payment_intent_id VARCHAR(255) NULL,
    source_order_id BIGINT NULL,
    migration_key VARCHAR(255) NULL,
    purchased_at DATETIME NOT NULL,
    expires_at DATETIME NULL,
    paused_at DATETIME NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_addon_grant_session (stripe_session_id),
    UNIQUE KEY uk_user_addon_grant_migration (migration_key),
    INDEX idx_user_addon_grant_user_feature_status_expiry (clerk_user_id, feature_code, status, expires_at),
    INDEX idx_user_addon_grant_user_feature_created (clerk_user_id, feature_code, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Per-purchase add-on grants for V2 subscription billing';
