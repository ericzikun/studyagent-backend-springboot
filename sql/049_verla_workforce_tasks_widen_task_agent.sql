-- ============================================================================
-- 049: 扩大 verla_workforce_tasks.task_agent 列宽
--
-- 背景：task_agent 描述来自 Python agent prompt 模板，实际长度可超 128 字符
--       （如 "Assignment Compose Expert: integrates all specialist outputs..."）。
--       VARCHAR(128) 溢出导致 DataIntegrityViolationException → 事件 nack 进 DLX
--       → inbox cursor 卡死 → 后续所有事件（含 artifact.updated）永久阻塞。
--
-- 修复：将 task_agent 改为 TEXT，task_name 同步扩到 VARCHAR(512) 以防同类问题。
-- ============================================================================

ALTER TABLE verla_workforce_tasks
    MODIFY COLUMN task_agent  TEXT          DEFAULT NULL COMMENT '分配的 worker role（TEXT，无长度限制）',
    MODIFY COLUMN task_name   VARCHAR(512)  DEFAULT NULL COMMENT '子任务标题';
