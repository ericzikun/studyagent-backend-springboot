-- ========================================
-- 迁移脚本 023: humanizer_tasks 增加 quota_ledger_id 列
-- 创建日期: 2026-03-04
-- 说明: 记录额度消费流水 ID，任务失败时用于退款
-- ========================================

USE studyagent;

ALTER TABLE humanizer_tasks
    ADD COLUMN quota_ledger_id BIGINT NULL COMMENT 'Quota ledger ID for refund on failure'
    AFTER retry_count;

-- 插入 ai_detection 套餐
INSERT INTO ai_feature_packages (feature_code, package_code, package_name, quota_amount, price_cents, currency, stripe_price_id, is_active, display_order, created_at, updated_at)
VALUES
    ('ai_detection', 'detection_10k',  '10,000 Words',  10000,  199, 'usd', 'prod_U3rHjd4FP8FWVP', 1, 20, NOW(), NOW()),
    ('ai_detection', 'detection_50k',  '50,000 Words',  50000,  799, 'usd', 'prod_U4Ir9EaiF86M9L', 1, 21, NOW(), NOW()),
    ('ai_detection', 'detection_200k', '200,000 Words', 200000, 2399, 'usd', 'prod_U4IrXGiodFvEnP', 1, 22, NOW(), NOW())
ON DUPLICATE KEY UPDATE stripe_price_id = VALUES(stripe_price_id), updated_at = NOW();

-- 插入 humanizer 套餐
INSERT INTO ai_feature_packages (feature_code, package_code, package_name, quota_amount, price_cents, currency, stripe_price_id, is_active, display_order, created_at, updated_at)
VALUES
    ('humanizer', 'humanizer_10k',  '10,000 Words',  10000,  299, 'usd', 'prod_U3rILaCQJBBX4D', 1, 30, NOW(), NOW()),
    ('humanizer', 'humanizer_50k',  '50,000 Words',  50000, 1199, 'usd', 'prod_U4IoPwCYzuWaaX', 1, 31, NOW(), NOW()),
    ('humanizer', 'humanizer_200k', '200,000 Words', 200000, 3999, 'usd', 'prod_U4Iop2oLxIj4mc', 1, 32, NOW(), NOW())
ON DUPLICATE KEY UPDATE stripe_price_id = VALUES(stripe_price_id), updated_at = NOW();

SELECT '✅ Migration 023: quota_ledger_id added, humanizer free quota updated, packages inserted' AS result;
