-- Basic paid trial SKUs aligned with frontend contract:
--   offer_kind=basic_paid_trial, tier=basic, trial_days=7, converts_to_plan_code=basic_*
-- Stripe product/price ids remain NULL in source control; inject per environment.
-- Both trial SKUs share the same $2.99 intro charge (weekly Stripe Price); conversion
-- target differs by billing_interval (month → basic_monthly, year → basic_yearly).

USE studyagent;

ALTER TABLE subscription_plans
    ADD COLUMN offer_kind VARCHAR(32) NOT NULL DEFAULT 'standard_plan'
        COMMENT 'standard_plan | basic_paid_trial' AFTER billing_interval,
    ADD COLUMN trial_days INT NULL
        COMMENT 'Paid-trial length in days; NULL for standard plans' AFTER offer_kind,
    ADD COLUMN converts_to_plan_code VARCHAR(64) NULL
        COMMENT 'Plan code after paid-trial conversion' AFTER trial_days;

-- Remove early draft SKU if present from local experiments.
DELETE FROM subscription_plans WHERE plan_code = 'basic_trial_weekly';

INSERT INTO subscription_plans (
    plan_code, tier, billing_interval, offer_kind, trial_days, converts_to_plan_code,
    price_cents, currency,
    assignment_quota, detection_quota, humanizer_quota,
    max_files, max_followup_edits, allowed_output_types,
    config_version, is_active, display_order
) VALUES
    (
        'basic_trial_to_monthly', 'basic', 'month', 'basic_paid_trial', 7, 'basic_monthly',
        299, 'usd',
        1, 3000, 1000,
        3, 3, JSON_ARRAY('writing'),
        1, 1, 5
    ),
    (
        'basic_trial_to_yearly', 'basic', 'year', 'basic_paid_trial', 7, 'basic_yearly',
        299, 'usd',
        1, 3000, 1000,
        3, 3, JSON_ARRAY('writing'),
        1, 1, 6
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
