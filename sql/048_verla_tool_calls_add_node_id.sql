-- ============================================================================
-- 048: verla_tool_calls 追加 node_id 列
--
-- 将工具调用关联到 workforce 任务节点（verla_workforce_tasks.node_id）。
-- 非 workforce 场景该列为 NULL。
-- ============================================================================

ALTER TABLE verla_tool_calls
    ADD COLUMN node_id VARCHAR(128) DEFAULT NULL
        COMMENT 'Workforce 任务节点 ID，对应 verla_workforce_tasks.node_id；非 workforce 场景为 NULL'
        AFTER step_id;

ALTER TABLE verla_tool_calls
    ADD KEY idx_session_node (session_id, node_id);
