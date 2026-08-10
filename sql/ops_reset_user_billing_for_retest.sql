-- Ops: reset one user's billing / trial / purchase state for retest.
-- Makes the account look like a fresh unpaid user for Pro Trial checkout.
--
-- IMPORTANT
-- 1) Replace the uid string below.
-- 2) Prefer Sandbox. Production only with explicit ops approval.
-- 3) This clears LOCAL DB only. If the Stripe Customer still has
--    metadata intro_trial_used=true, Pro Trial checkout will still fail (1029).
--    After this script: in Stripe Dashboard open the customer → Metadata →
--    delete intro_trial_used, OR delete the Test customer entirely
--    (local stripe_customer_id is cleared so backend will create a new one).
-- 4) Does NOT delete Clerk account / user_profiles / Verla conversations.
--
-- Collation: session vars default to utf8mb4_0900_ai_ci on MySQL 8, while
-- billing tables use utf8mb4_unicode_ci — always cast @uid explicitly.

USE studyagent;

-- ========== CONFIG ==========
SET @uid = CONVERT('user_REPLACE_ME' USING utf8mb4) COLLATE utf8mb4_unicode_ci;
-- ============================

START TRANSACTION;

-- Preview before delete (optional check)
SELECT 'user_subscriptions' AS src, COUNT(*) AS cnt FROM user_subscriptions WHERE clerk_user_id = @uid
UNION ALL SELECT 'recharge_orders', COUNT(*) FROM recharge_orders WHERE clerk_user_id = @uid
UNION ALL SELECT 'user_ai_quotas', COUNT(*) FROM user_ai_quotas WHERE clerk_user_id = @uid
UNION ALL SELECT 'quota_ledger', COUNT(*) FROM quota_ledger WHERE clerk_user_id = @uid
UNION ALL SELECT 'user_addon_grants', COUNT(*) FROM user_addon_grants WHERE clerk_user_id = @uid
UNION ALL SELECT 'payment_resume_context', COUNT(*) FROM payment_resume_context WHERE clerk_user_id = @uid
UNION ALL SELECT 'quota_vip_users', COUNT(*) FROM quota_vip_users WHERE clerk_user_id = @uid
UNION ALL SELECT 'verla_followup_edit_usages', COUNT(*) FROM verla_followup_edit_usages WHERE clerk_user_id = @uid;

-- Ledger allocations for this user's ledgers
DELETE a
FROM quota_ledger_allocations a
JOIN quota_ledger l ON l.id = a.quota_ledger_id
WHERE l.clerk_user_id = @uid;

DELETE FROM quota_ledger WHERE clerk_user_id = @uid;
DELETE FROM user_addon_grants WHERE clerk_user_id = @uid;
DELETE FROM user_ai_quotas WHERE clerk_user_id = @uid;
DELETE FROM recharge_orders WHERE clerk_user_id = @uid;
DELETE FROM payment_resume_context WHERE clerk_user_id = @uid;
DELETE FROM verla_followup_edit_usages WHERE clerk_user_id = @uid;
DELETE FROM quota_vip_users WHERE clerk_user_id = @uid;

-- Reset subscription mirror to unpaid / eligible-for-trial
UPDATE user_subscriptions
SET tier = 'free',
    plan_code = NULL,
    status = 'free',
    stripe_customer_id = NULL,
    stripe_subscription_id = NULL,
    stripe_schedule_id = NULL,
    current_period_start = NULL,
    current_period_end = NULL,
    quota_period_start = NULL,
    quota_period_end = NULL,
    cancel_at_period_end = 0,
    pending_plan_code = NULL,
    pending_effective_at = NULL,
    pending_upgrade_order_no = NULL,
    pending_upgrade_expires_at = NULL,
    grace_end_at = NULL,
    intro_trial_used_at = NULL,
    intro_trial_converted_at = NULL,
    subscription_phase = NULL,
    last_synced_at = NULL,
    last_stripe_event_created_at = NULL,
    last_stripe_event_id = NULL,
    version = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE clerk_user_id = @uid;

-- If no subscription row yet, insert a clean free row (optional)
INSERT INTO user_subscriptions (clerk_user_id, tier, status, cancel_at_period_end, version)
SELECT @uid, 'free', 'free', 0, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM user_subscriptions WHERE clerk_user_id = @uid
);

COMMIT;

-- Verify
SELECT clerk_user_id, tier, plan_code, status,
       stripe_customer_id, stripe_subscription_id, stripe_schedule_id,
       intro_trial_used_at, intro_trial_converted_at, subscription_phase
FROM user_subscriptions
WHERE clerk_user_id = @uid;

SELECT 'recharge_orders' AS src, COUNT(*) AS cnt FROM recharge_orders WHERE clerk_user_id = @uid
UNION ALL SELECT 'user_ai_quotas', COUNT(*) FROM user_ai_quotas WHERE clerk_user_id = @uid
UNION ALL SELECT 'quota_ledger', COUNT(*) FROM quota_ledger WHERE clerk_user_id = @uid
UNION ALL SELECT 'user_addon_grants', COUNT(*) FROM user_addon_grants WHERE clerk_user_id = @uid;
