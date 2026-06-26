-- Migrate legacy V1 paid balances into expiring V2 add-on grants.
-- Run once in production. Safe to re-run because migration_key is unique.

USE studyagent;

SET @legacy_migration_now = NOW();
SET @legacy_migration_expires_at = DATE_ADD(@legacy_migration_now, INTERVAL 12 MONTH);

CREATE TABLE IF NOT EXISTS user_ai_quotas_legacy_paid_balance_backup (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    source_quota_id BIGINT NOT NULL COMMENT 'Original user_ai_quotas.id',
    clerk_user_id VARCHAR(255) NOT NULL COMMENT 'Clerk user id',
    feature_code VARCHAR(64) NOT NULL COMMENT 'Feature code',
    legacy_paid_balance BIGINT NOT NULL COMMENT 'Original paid_balance before migration',
    migration_key VARCHAR(255) NOT NULL COMMENT 'Idempotency key for legacy migration snapshot',
    snapshot_at DATETIME NOT NULL COMMENT 'When this legacy balance snapshot was captured',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
    PRIMARY KEY (id),
    UNIQUE KEY uk_legacy_paid_balance_backup_migration (migration_key),
    KEY idx_legacy_paid_balance_backup_user_feature (clerk_user_id, feature_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Backup snapshots of legacy paid_balance before addon-grant migration';

INSERT INTO user_ai_quotas_legacy_paid_balance_backup (
    source_quota_id,
    clerk_user_id,
    feature_code,
    legacy_paid_balance,
    migration_key,
    snapshot_at,
    created_at,
    updated_at
)
SELECT
    q.id,
    q.clerk_user_id,
    q.feature_code,
    q.paid_balance,
    CONCAT('legacy:', q.clerk_user_id, ':', q.feature_code),
    @legacy_migration_now,
    @legacy_migration_now,
    @legacy_migration_now
FROM user_ai_quotas q
WHERE q.paid_balance > 0
  AND NOT EXISTS (
    SELECT 1
    FROM user_ai_quotas_legacy_paid_balance_backup b
    WHERE b.migration_key = CONCAT('legacy:', q.clerk_user_id, ':', q.feature_code)
  );

INSERT INTO user_addon_grants (
    clerk_user_id,
    feature_code,
    grant_type,
    addon_code,
    status,
    initial_amount,
    remaining_amount,
    stripe_session_id,
    stripe_payment_intent_id,
    source_order_id,
    migration_key,
    purchased_at,
    expires_at,
    paused_at,
    version,
    created_at,
    updated_at
)
SELECT
    q.clerk_user_id,
    q.feature_code,
    'legacy_migration',
    NULL,
    'active',
    q.paid_balance,
    q.paid_balance,
    NULL,
    NULL,
    NULL,
    CONCAT('legacy:', q.clerk_user_id, ':', q.feature_code),
    @legacy_migration_now,
    @legacy_migration_expires_at,
    NULL,
    0,
    @legacy_migration_now,
    @legacy_migration_now
FROM user_ai_quotas q
WHERE q.paid_balance > 0
  AND NOT EXISTS (
    SELECT 1
    FROM user_addon_grants g
    WHERE g.migration_key = CONCAT('legacy:', q.clerk_user_id, ':', q.feature_code)
  );

UPDATE user_ai_quotas q
SET q.paid_balance = 0,
    q.version = q.version + 1,
    q.updated_at = NOW()
WHERE q.paid_balance > 0
  AND EXISTS (
    SELECT 1
    FROM user_addon_grants g
    WHERE g.migration_key = CONCAT('legacy:', q.clerk_user_id, ':', q.feature_code)
  );
