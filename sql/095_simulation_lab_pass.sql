-- Simulation Lab 7-day pass: one-time $1.99 purchase that unlocks unlimited
-- lab creation for 7 days. Independent of subscriptions and Study Passes.
-- Idempotent; safe to re-run. The catalog row carries the environment-specific
-- Stripe Price id so production can point at its own live price.

USE studyagent;

CREATE TABLE IF NOT EXISTS simulation_lab_pass_products (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pass_code VARCHAR(64) NOT NULL,
    stripe_price_id VARCHAR(255) NOT NULL,
    amount_cents INT NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'usd',
    access_days INT NOT NULL DEFAULT 7,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sim_lab_pass_code (pass_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Simulation Lab pass catalog (one-time purchase)';

CREATE TABLE IF NOT EXISTS simulation_lab_passes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    clerk_user_id VARCHAR(64) NOT NULL,
    pass_code VARCHAR(64) NOT NULL,
    source VARCHAR(24) NOT NULL DEFAULT 'stripe',
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    started_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    stripe_checkout_session_id VARCHAR(255) NULL,
    stripe_payment_intent_id VARCHAR(255) NULL,
    amount_cents INT NULL,
    currency VARCHAR(8) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sim_lab_pass_session (stripe_checkout_session_id),
    KEY idx_sim_lab_pass_user (clerk_user_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Simulation Lab pass entitlements (stripe or comp)';

INSERT INTO simulation_lab_pass_products
    (pass_code, stripe_price_id, amount_cents, currency, access_days, is_active)
VALUES
    ('simulation_lab_7d', 'price_1UaJ377GRT6LLkI1Wez9g0Lj', 199, 'usd', 7, 1)
ON DUPLICATE KEY UPDATE
    stripe_price_id = VALUES(stripe_price_id),
    amount_cents = VALUES(amount_cents),
    currency = VALUES(currency),
    access_days = VALUES(access_days),
    is_active = VALUES(is_active);
