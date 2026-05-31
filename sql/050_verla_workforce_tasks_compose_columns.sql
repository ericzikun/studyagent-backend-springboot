-- ============================================================================
-- 050: verla_workforce_tasks 新增 compose 轮次字段
--
-- 背景：compose-progress 节点现在以 node_kind='compose' 入库，
--       需要两个字段存储当前轮次和总轮次。
-- ============================================================================

ALTER TABLE verla_workforce_tasks
    ADD COLUMN compose_current_round SMALLINT DEFAULT NULL
        COMMENT 'compose 节点：当前已完成的 compose 轮次'
        AFTER plan_task_count,
    ADD COLUMN compose_total_rounds  SMALLINT DEFAULT NULL
        COMMENT 'compose / plan 节点：compose 总轮次'
        AFTER compose_current_round;

-- node_kind 注释更新：plan / task / compose
ALTER TABLE verla_workforce_tasks
    MODIFY COLUMN node_kind VARCHAR(16) NOT NULL DEFAULT 'task'
        COMMENT 'plan / task / compose';
