-- Pro Trial → subscription model (mirror Basic Trial).
--   offer_kind=pro_paid_trial, tier=pro, trial_days=7
--   Phase1: weekly US$2.99 recurring Stripe Price
--   Phase2: Schedule converts to pro_monthly / pro_yearly (full price)
-- Retires one-time SKU pro_trial_once from new sales.
--
-- Prerequisites: 071 / 072 / 073 / 074 / 075 already applied.
-- One-shot: upserts SKUs + binds Stripe weekly Prices below.
-- Idempotent — safe to re-run.

USE studyagent;

START TRANSACTION;

-- 1) Retire one-time Pro Trial from catalog / new Checkout
UPDATE subscription_plans
SET is_active = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code = 'pro_trial_once';

-- 2) Subscription Pro Trial SKUs (week charge; billing_interval = conversion intent)
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

-- 3) Bind Stripe IDs (same Product; two weekly recurring $2.99 Prices)
--    Confirm Live vs Sandbox matches backend Stripe key.
UPDATE subscription_plans
SET stripe_product_id = 'prod_V2g1XTzV22Xlej',
    stripe_price_id = 'price_1U2sET7GRT6LLkI1G01bZsnM',
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code = 'pro_trial_to_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_V2g1XTzV22Xlej',
    stripe_price_id = 'price_1U2sET7GRT6LLkI1RiBWMZzT',
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code = 'pro_trial_to_yearly';

SELECT plan_code, tier, billing_interval, offer_kind, trial_days,
       converts_to_plan_code, price_cents, is_active,
       stripe_product_id, stripe_price_id,
       assignment_quota, detection_quota, humanizer_quota,
       max_files, max_followup_edits
FROM subscription_plans
WHERE plan_code IN (
    'pro_trial_once',
    'pro_trial_to_monthly',
    'pro_trial_to_yearly',
    'pro_monthly',
    'pro_yearly'
)
ORDER BY plan_code;
