-- Pro Trial: one-time US$2.99 for a single 7-day entitlement window.
-- No auto-renew, no Subscription Schedule, no converts_to_plan_code.
-- Entitlements match former Basic Trial; Basic Trial SKUs are retired from sale.
--
-- Prerequisites: 071 / 072 / 073 / 074 already applied.
-- Stripe: create a one_time Price (NOT recurring). Bind prod_/price_ per env after this script.
--
-- Backend must fulfill via Checkout Mode.PAYMENT (existing Intro Trial is Mode.SUBSCRIPTION).

USE studyagent;

START TRANSACTION;

-- 1) Retire Basic Trial from catalog / new Checkout (keep rows for history)
UPDATE subscription_plans
SET is_active = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code IN ('basic_trial_to_monthly', 'basic_trial_to_yearly');

-- 2) Pro Trial one-time SKU
INSERT INTO subscription_plans (
    plan_code, tier, billing_interval, offer_kind, trial_days, converts_to_plan_code,
    price_cents, currency,
    assignment_quota, detection_quota, humanizer_quota,
    max_files, max_followup_edits, allowed_output_types,
    config_version, is_active, display_order
) VALUES (
    'pro_trial_once', 'pro', 'once', 'pro_paid_trial', 7, NULL,
    299, 'usd',
    1, 3000, 1000,
    3, 3, JSON_ARRAY('writing'),
    1, 1, 4
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

-- 3) Bind Stripe IDs (one_time $2.99 Price — confirm Live vs Sandbox matches backend key)
UPDATE subscription_plans
SET stripe_product_id = 'prod_V2g1XTzV22Xlej',
    stripe_price_id = 'price_1U2aez7GRT6LLkI1tuoEiphT',
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code = 'pro_trial_once';

SELECT plan_code, tier, billing_interval, offer_kind, trial_days,
       converts_to_plan_code, price_cents, is_active,
       stripe_product_id, stripe_price_id,
       assignment_quota, detection_quota, humanizer_quota,
       max_files, max_followup_edits
FROM subscription_plans
WHERE plan_code IN (
    'pro_trial_once',
    'basic_trial_to_monthly',
    'basic_trial_to_yearly'
)
ORDER BY plan_code;
