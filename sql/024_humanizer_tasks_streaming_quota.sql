-- ========================================
-- 迁移脚本 024: humanizer_tasks 支持流式逐块扣费
-- 创建日期: 2026-03-08
-- 说明:
--   - 新增 total_words: 任务总 word 数
--   - 新增 consumed_words: 已扣费的 word 数
--   - 支持 QUOTA_EXHAUSTED 状态: 检测中途余额耗尽，等待充值后继续
-- ========================================

USE studyagent;

ALTER TABLE humanizer_tasks
    ADD COLUMN total_words INT NOT NULL DEFAULT 0 COMMENT 'Total word count of input text'
    AFTER quota_ledger_id,
    ADD COLUMN consumed_words INT NOT NULL DEFAULT 0 COMMENT 'Words consumed (quota deducted) so far, for streaming per-chunk billing'
    AFTER total_words;

SELECT '✅ Migration 024: streaming quota columns added (total_words, consumed_words)' AS result;
