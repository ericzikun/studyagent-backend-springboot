-- V2 billing catalog: subscription plans and add-on packages.
-- Stripe product/price ids are intentionally NULL in source control.
-- Fill them in the target environment after creating Sandbox/Live prices.

USE studyagent;

CREATE TABLE IF NOT EXISTS subscription_plans (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_code VARCHAR(64) NOT NULL,
    tier VARCHAR(16) NOT NULL,
    billing_interval VARCHAR(16) NOT NULL,
    stripe_product_id VARCHAR(255) NULL,
    stripe_price_id VARCHAR(255) NULL,
    price_cents INT NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'usd',
    assignment_quota BIGINT NOT NULL,
    detection_quota BIGINT NOT NULL,
    humanizer_quota BIGINT NOT NULL,
    max_files INT NULL COMMENT 'NULL means unlimited',
    max_followup_edits INT NULL COMMENT 'NULL means unlimited',
    allowed_output_types JSON NOT NULL,
    config_version INT NOT NULL DEFAULT 1,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    display_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_subscription_plan_code (plan_code),
    UNIQUE KEY uk_subscription_stripe_price (stripe_price_id),
    INDEX idx_subscription_tier_interval (tier, billing_interval),
    INDEX idx_subscription_active_order (is_active, display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='V2 subscription plan and Stripe Price catalog';

INSERT INTO subscription_plans (
    plan_code, tier, billing_interval, price_cents, currency,
    assignment_quota, detection_quota, humanizer_quota,
    max_files, max_followup_edits, allowed_output_types,
    config_version, is_active, display_order
) VALUES
    ('basic_monthly', 'basic', 'month', 1999, 'usd', 3, 3, 2, 5, 5, JSON_ARRAY('writing'), 1, 1, 10),
    ('basic_yearly',  'basic', 'year',  9588, 'usd', 3, 3, 2, 5, 5, JSON_ARRAY('writing'), 1, 1, 11),
    ('plus_monthly',  'plus',  'month', 3999, 'usd', 8, 8, 5, 10, 10, JSON_ARRAY('writing', 'ppt', 'coding'), 1, 1, 20),
    ('plus_yearly',   'plus',  'year', 19188, 'usd', 8, 8, 5, 10, 10, JSON_ARRAY('writing', 'ppt', 'coding'), 1, 1, 21),
    ('pro_monthly',   'pro',   'month', 7999, 'usd', 16, 16, 10, NULL, NULL, JSON_ARRAY('writing', 'ppt', 'coding'), 1, 1, 30),
    ('pro_yearly',    'pro',   'year', 38388, 'usd', 16, 16, 10, NULL, NULL, JSON_ARRAY('writing', 'ppt', 'coding'), 1, 1, 31)
ON DUPLICATE KEY UPDATE
    tier = VALUES(tier),
    billing_interval = VALUES(billing_interval),
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

CREATE TABLE IF NOT EXISTS addon_package_defs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    addon_code VARCHAR(64) NOT NULL,
    feature_code VARCHAR(64) NOT NULL,
    stripe_product_id VARCHAR(255) NULL,
    stripe_price_id VARCHAR(255) NULL,
    quota_amount BIGINT NOT NULL,
    validity_months INT NOT NULL DEFAULT 2,
    price_cents INT NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'usd',
    config_version INT NOT NULL DEFAULT 1,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    display_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_addon_code (addon_code),
    UNIQUE KEY uk_addon_stripe_price (stripe_price_id),
    INDEX idx_addon_feature_active (feature_code, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='V2 one-time add-on package catalog';

INSERT INTO addon_package_defs (
    addon_code, feature_code, quota_amount, validity_months,
    price_cents, currency, config_version, is_active, display_order
) VALUES
    ('addon_assignment_3', 'task_create', 3, 2, 999, 'usd', 1, 1, 10),
    ('addon_detection_5', 'ai_detection', 5, 2, 499, 'usd', 1, 1, 20),
    ('addon_humanizer_3', 'humanizer', 3, 2, 699, 'usd', 1, 1, 30)
ON DUPLICATE KEY UPDATE
    feature_code = VALUES(feature_code),
    quota_amount = VALUES(quota_amount),
    validity_months = VALUES(validity_months),
    price_cents = VALUES(price_cents),
    currency = VALUES(currency),
    config_version = VALUES(config_version),
    is_active = VALUES(is_active),
    display_order = VALUES(display_order),
    updated_at = CURRENT_TIMESTAMP;

-- After creating Stripe Sandbox prices, run environment-specific updates like:
-- UPDATE subscription_plans SET stripe_product_id='prod_...', stripe_price_id='price_...' WHERE plan_code='basic_monthly';
-- UPDATE addon_package_defs SET stripe_product_id='prod_...', stripe_price_id='price_...' WHERE addon_code='addon_assignment_3';
