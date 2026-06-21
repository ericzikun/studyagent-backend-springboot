-- Migrate legacy V1 paid balances into expiring V2 add-on grants.
-- Run once in production. Safe to re-run because migration_key is unique.

USE studyagent;

SET @legacy_migration_now = NOW();
SET @legacy_migration_expires_at = DATE_ADD(@legacy_migration_now, INTERVAL 12 MONTH);

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
