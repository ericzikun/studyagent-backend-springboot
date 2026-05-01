-- ========================================
-- 028: mq_outbox.task_id 允许 NULL（Verla 不写 task_id）
-- 与 docker-aliyun-20260125/migrations/040_mq_outbox_task_id_nullable.sql 语义一致
-- ========================================

USE studyagent;

ALTER TABLE mq_outbox
    MODIFY COLUMN task_id BIGINT NULL COMMENT '老链路 Task.id；Verla 命令为 NULL';
