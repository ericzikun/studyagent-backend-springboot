-- ========================================
-- 迁移脚本 025: humanizer_tasks 新增 source + result_hash 字段
-- 创建日期: 2026-03-13
-- 说明:
--   - 新增 source: 任务来源标识（如 web、api、extension 等）
--   - 新增 result_hash: HUMANIZE 结果文本前200字符的 SHA-256
--     用于 DETECT 时快速匹配用户自己 humanize 过的内容
-- ========================================

USE studyagent;

ALTER TABLE humanizer_tasks
    ADD COLUMN source VARCHAR(32) DEFAULT NULL COMMENT 'Task source identifier (e.g. web, api, extension)'
    AFTER clerk_user_id,
    ADD COLUMN result_hash VARCHAR(64) DEFAULT NULL COMMENT 'SHA-256 of first 200 chars of result_text, for relaxed detect matching'
    AFTER result_text;

CREATE INDEX idx_humanizer_tasks_result_hash ON humanizer_tasks (clerk_user_id, result_hash);

SELECT '✅ Migration 025: source and result_hash columns added to humanizer_tasks' AS result;
