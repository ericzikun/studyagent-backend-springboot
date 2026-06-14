-- ========================================
-- 迁移脚本 056: humanizer_tasks 增加 task_name 列
-- 创建日期: 2026-06-14
-- 说明:
--   - 新增 task_name: 由 Python ConversationTitleService 通过 MQ 生成的任务标题
--     （提交时下发 cmd.plan.task_name，PLAN_TASK_NAME_RESOLVED 回写）。
--   - best-effort：标题生成失败时保持 NULL，不影响检测/改写主流程。
-- ========================================

USE studyagent;

ALTER TABLE humanizer_tasks
    ADD COLUMN task_name VARCHAR(255) NULL COMMENT 'Generated task title (ConversationTitleService via MQ)'
    AFTER source;

SELECT '✅ Migration 056: humanizer_tasks.task_name column added' AS result;
