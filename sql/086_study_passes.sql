-- Study Pass: a one-time, 30-day reading entitlement for the study library
-- (/questions and /knowledge). It is deliberately independent of
-- user_subscriptions, which mirrors exactly one Stripe subscription per user.
--
-- The catalog row ships inactive so nothing is sellable before a Stripe Price
-- exists. Flip is_active to 1 (and fill the Stripe ids) with the
-- environment-only script, following v2_billing_stripe_sandbox_ids.example.sql.
--
-- Idempotent — safe to re-run on Sandbox and Production.

USE studyagent;

CREATE TABLE IF NOT EXISTS study_pass_products (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pass_code VARCHAR(64) NOT NULL,
    stripe_product_id VARCHAR(255) NULL,
    stripe_price_id VARCHAR(255) NULL,
    price_cents INT NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'usd',
    validity_days INT NOT NULL DEFAULT 30,
    config_version INT NOT NULL DEFAULT 1,
    is_active TINYINT(1) NOT NULL DEFAULT 0,
    display_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_study_pass_code (pass_code),
    UNIQUE KEY uk_study_pass_stripe_price (stripe_price_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Study Pass one-time purchase catalog';

INSERT INTO study_pass_products (
    pass_code, price_cents, currency, validity_days,
    config_version, is_active, display_order
) VALUES
    ('study_pass_30d', 99, 'usd', 30, 1, 0, 10)
ON DUPLICATE KEY UPDATE
    price_cents = VALUES(price_cents),
    currency = VALUES(currency),
    validity_days = VALUES(validity_days),
    config_version = VALUES(config_version),
    display_order = VALUES(display_order),
    updated_at = CURRENT_TIMESTAMP;

-- One row per purchased pass. A user accumulates rows over time; only a row
-- with status='active' and expires_at in the future grants reading access.
CREATE TABLE IF NOT EXISTS study_passes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    clerk_user_id VARCHAR(255) NOT NULL,
    pass_code VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    started_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    order_id BIGINT NULL,
    stripe_checkout_session_id VARCHAR(255) NULL,
    stripe_payment_intent_id VARCHAR(255) NULL,
    upgrade_credit_used_at DATETIME NULL,
    upgrade_credit_coupon_id VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_study_pass_session (stripe_checkout_session_id),
    UNIQUE KEY uk_study_pass_payment_intent (stripe_payment_intent_id),
    INDEX idx_study_pass_user_window (clerk_user_id, status, expires_at),
    INDEX idx_study_pass_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Purchased Study Pass reading entitlements (one row per purchase)';

SELECT pass_code, price_cents, currency, validity_days, is_active, stripe_price_id
FROM study_pass_products
WHERE pass_code = 'study_pass_30d';
