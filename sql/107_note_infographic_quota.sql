-- AI Notes commercialization:
--   * Notes / mind map / podcast / quiz stay unlimited for paid members.
--   * Knowledge image is metered per delivered image.
--   * Trial 5; Basic 35; Plus 65; Pro 135, refreshed monthly (annual plans
--     use the same monthly window as the other plan quotas).
-- One-shot migration. Apply with --default-character-set=utf8mb4.

USE studyagent;

ALTER TABLE subscription_plans
    ADD COLUMN note_infographic_quota BIGINT NOT NULL DEFAULT 0 AFTER humanizer_quota;

UPDATE subscription_plans
SET note_infographic_quota = CASE
        WHEN plan_code IN (
            'pro_trial_to_monthly', 'pro_trial_to_yearly', 'pro_trial_once',
            'basic_trial_to_monthly', 'basic_trial_to_yearly', 'basic_trial_weekly'
        ) THEN 5
        WHEN tier = 'basic' THEN 35
        WHEN tier = 'plus' THEN 65
        WHEN tier = 'pro' THEN 135
        ELSE 0
    END,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO ai_feature_defs (
    feature_code, feature_name, quota_unit, free_quota_period,
    free_quota_amount, is_active, display_order
) VALUES (
    'note_infographic', 'Infographics', 'count', 'monthly',
    0, 1, 40
)
ON DUPLICATE KEY UPDATE
    feature_name = VALUES(feature_name),
    quota_unit = VALUES(quota_unit),
    free_quota_period = VALUES(free_quota_period),
    free_quota_amount = VALUES(free_quota_amount),
    is_active = VALUES(is_active),
    display_order = VALUES(display_order),
    updated_at = CURRENT_TIMESTAMP;

ALTER TABLE demo_note_conversation
    ADD COLUMN infographic_status VARCHAR(32) NOT NULL DEFAULT 'NONE' AFTER infographic_url;

UPDATE demo_note_conversation
SET infographic_status = 'READY'
WHERE infographic_url IS NOT NULL AND infographic_url <> '';
