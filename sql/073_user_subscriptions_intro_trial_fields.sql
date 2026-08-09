-- Intro Trial once-per-Stripe-Customer tracking on user_subscriptions.

USE studyagent;

ALTER TABLE user_subscriptions
    ADD COLUMN intro_trial_used_at DATETIME NULL COMMENT 'First Basic Trial payment time; non-null blocks re-trial' AFTER grace_end_at,
    ADD COLUMN intro_trial_converted_at DATETIME NULL COMMENT 'Trial → basic_monthly conversion time' AFTER intro_trial_used_at,
    ADD COLUMN subscription_phase VARCHAR(32) NULL COMMENT 'intro | standard | NULL' AFTER intro_trial_converted_at;

CREATE INDEX idx_user_subscription_intro_trial_used
    ON user_subscriptions (intro_trial_used_at);
