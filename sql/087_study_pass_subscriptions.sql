-- Run against the same selected business database as 086_study_passes.sql.
-- Legacy purchases remain one-time. No credentials or environment-specific Stripe ids.
SET @study_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='study_passes' AND column_name='stripe_subscription_id')=0, 'ALTER TABLE study_passes ADD COLUMN stripe_subscription_id VARCHAR(255) NULL', 'SELECT 1');
PREPARE study_stmt FROM @study_ddl; EXECUTE study_stmt; DEALLOCATE PREPARE study_stmt;
SET @study_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='study_passes' AND column_name='cancel_at_period_end')=0, 'ALTER TABLE study_passes ADD COLUMN cancel_at_period_end TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE study_stmt FROM @study_ddl; EXECUTE study_stmt; DEALLOCATE PREPARE study_stmt;
SET @study_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='study_passes' AND column_name='last_paid_invoice_id')=0, 'ALTER TABLE study_passes ADD COLUMN last_paid_invoice_id VARCHAR(255) NULL', 'SELECT 1');
PREPARE study_stmt FROM @study_ddl; EXECUTE study_stmt; DEALLOCATE PREPARE study_stmt;
SET @study_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='study_passes' AND column_name='renewal_price_cents')=0, 'ALTER TABLE study_passes ADD COLUMN renewal_price_cents INT NULL', 'SELECT 1');
PREPARE study_stmt FROM @study_ddl; EXECUTE study_stmt; DEALLOCATE PREPARE study_stmt;
SET @study_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='study_passes' AND column_name='renewal_currency')=0, 'ALTER TABLE study_passes ADD COLUMN renewal_currency VARCHAR(8) NULL', 'SELECT 1');
PREPARE study_stmt FROM @study_ddl; EXECUTE study_stmt; DEALLOCATE PREPARE study_stmt;
SET @study_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='study_passes' AND column_name='billing_interval_days')=0, 'ALTER TABLE study_passes ADD COLUMN billing_interval_days INT NULL', 'SELECT 1');
PREPARE study_stmt FROM @study_ddl; EXECUTE study_stmt; DEALLOCATE PREPARE study_stmt;
SET @study_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='study_pass_products' AND column_name='billing_type')=0, 'ALTER TABLE study_pass_products ADD COLUMN billing_type VARCHAR(32) NOT NULL DEFAULT ''one_time''', 'SELECT 1');
PREPARE study_stmt FROM @study_ddl; EXECUTE study_stmt; DEALLOCATE PREPARE study_stmt;
SET @study_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='study_passes' AND index_name='uk_study_pass_subscription')=0, 'ALTER TABLE study_passes ADD UNIQUE KEY uk_study_pass_subscription (stripe_subscription_id)', 'SELECT 1');
PREPARE study_stmt FROM @study_ddl; EXECUTE study_stmt; DEALLOCATE PREPARE study_stmt;

-- Stop offering new one-time purchases; retain rows for old checkout fulfillment.
UPDATE study_pass_products SET is_active=0 WHERE pass_code='study_pass_30d' AND billing_type='one_time';
INSERT INTO study_pass_products (pass_code,price_cents,currency,validity_days,billing_type,is_active,display_order)
VALUES ('study_pass_subscription_30d',99,'usd',30,'subscription',0,5)
ON DUPLICATE KEY UPDATE pass_code=VALUES(pass_code);
-- Configure a NEW recurring Stripe Price (interval=day, interval_count=30), then
-- set its ids and is_active=1 using an environment-only update. Never reuse the
-- old one-time Price. Re-running this migration will not reset the new product.

SET @study_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='study_passes' AND column_name='purchase_key')=0, 'ALTER TABLE study_passes ADD COLUMN purchase_key VARCHAR(64) NULL, ADD UNIQUE KEY uk_study_pass_purchase_key (purchase_key)', 'SELECT 1');
PREPARE study_stmt FROM @study_ddl; EXECUTE study_stmt; DEALLOCATE PREPARE study_stmt;

SET @study_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='study_passes' AND column_name='stripe_price_id')=0, 'ALTER TABLE study_passes ADD COLUMN stripe_price_id VARCHAR(255) NULL', 'SELECT 1');
PREPARE study_stmt FROM @study_ddl; EXECUTE study_stmt; DEALLOCATE PREPARE study_stmt;
