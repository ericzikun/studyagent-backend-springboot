-- Sandbox / test only. Do NOT run in Production.
-- One-shot: retire Basic Trial, upsert subscription Pro Trial SKUs, bind Sandbox Stripe IDs.
-- Idempotent — safe to re-run.
--
-- Stripe Sandbox:
--   Product: prod_V2yikGRbHLdZR6
--   Weekly Prices:
--     price_1U2slM7GRT6LLkI1RYM0atFA → pro_trial_to_monthly → pro_monthly
--     price_1U2sli7GRT6LLkI13rZpSxDG → pro_trial_to_yearly  → pro_yearly

USE studyagent;

START TRANSACTION;

-- 1) Retire Basic Trial from new sales (keep rows for history)
UPDATE subscription_plans
SET is_active = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code IN ('basic_trial_to_monthly', 'basic_trial_to_yearly', 'basic_trial_weekly');

-- 2) Retire historical one-time Pro Trial if present
UPDATE subscription_plans
SET is_active = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code = 'pro_trial_once';

-- 3) Upsert subscription Pro Trial SKUs
INSERT INTO subscription_plans (
    plan_code, tier, billing_interval, offer_kind, trial_days, converts_to_plan_code,
    price_cents, currency,
    assignment_quota, detection_quota, humanizer_quota,
    max_files, max_followup_edits, allowed_output_types,
    config_version, is_active, display_order
) VALUES
    (
        'pro_trial_to_monthly', 'pro', 'month', 'pro_paid_trial', 7, 'pro_monthly',
        299, 'usd',
        1, 3000, 1000,
        3, 3, JSON_ARRAY('writing'),
        1, 1, 4
    ),
    (
        'pro_trial_to_yearly', 'pro', 'year', 'pro_paid_trial', 7, 'pro_yearly',
        299, 'usd',
        1, 3000, 1000,
        3, 3, JSON_ARRAY('writing'),
        1, 1, 5
    )
ON DUPLICATE KEY UPDATE
    tier = VALUES(tier),
    billing_interval = VALUES(billing_interval),
    offer_kind = VALUES(offer_kind),
    trial_days = VALUES(trial_days),
    converts_to_plan_code = VALUES(converts_to_plan_code),
    price_cents = VALUES(price_cents),
    currency = VALUES(currency),
    assignment_quota = VALUES(assignment_quota),
    detection_quota = VALUES(detection_quota),
    humanizer_quota = VALUES(humanizer_quota),
    max_files = VALUES(max_files),
    max_followup_edits = VALUES(max_followup_edits),
    allowed_output_types = VALUES(allowed_output_types),
    config_version = VALUES(config_version),
    is_active = VALUES(is_active),
    display_order = VALUES(display_order),
    updated_at = CURRENT_TIMESTAMP;

COMMIT;

-- 4) Bind Sandbox Stripe IDs
UPDATE subscription_plans
SET stripe_product_id = 'prod_V2yikGRbHLdZR6',
    stripe_price_id = 'price_1U2slM7GRT6LLkI1RYM0atFA',
    is_active = 1,
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code = 'pro_trial_to_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_V2yikGRbHLdZR6',
    stripe_price_id = 'price_1U2sli7GRT6LLkI13rZpSxDG',
    is_active = 1,
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code = 'pro_trial_to_yearly';

SELECT plan_code, tier, billing_interval, offer_kind, trial_days,
       converts_to_plan_code, price_cents, is_active,
       stripe_product_id, stripe_price_id
FROM subscription_plans
WHERE plan_code IN (
    'basic_trial_to_monthly',
    'basic_trial_to_yearly',
    'pro_trial_once',
    'pro_trial_to_monthly',
    'pro_trial_to_yearly'
)
ORDER BY plan_code;
