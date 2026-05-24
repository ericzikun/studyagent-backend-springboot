-- ============================================================================
-- 047: Verla Workforce 任务持久化
--
-- 新增两张表：
--   verla_workforce_tasks        任务节点状态快照（task decompose / coordinate / info）
--   verla_workforce_task_outputs 任务产出内容（result_text / detail_items_json）
--
-- 驱动事件：ASSIGNMENT_AGENT_NODE_UPDATED / ASSIGNMENT_AGENT_NODE_DETAILED
-- ============================================================================

CREATE TABLE IF NOT EXISTS verla_workforce_tasks (
    id               BIGINT        NOT NULL AUTO_INCREMENT,

    -- Verla 三层上下文
    conversation_id  BIGINT        NOT NULL,
    turn_id          BIGINT        NOT NULL,
    session_id       BIGINT        NOT NULL,

    -- 节点身份
    node_id          VARCHAR(128)  NOT NULL    COMMENT '"assignment-plan" 或 "task-{camel_task_id}"',
    camel_task_id    VARCHAR(64)   DEFAULT NULL COMMENT 'CAMEL 原始 task_id，plan 节点为 NULL',
    node_kind        VARCHAR(16)   NOT NULL DEFAULT 'task' COMMENT 'plan / task',

    -- Task Info（来自 log_task_created）
    task_name        VARCHAR(255)  DEFAULT NULL COMMENT '子任务标题',
    task_type        VARCHAR(32)   DEFAULT NULL COMMENT 'ROOT / 空字符串（子任务）',
    description      TEXT          DEFAULT NULL COMMENT '任务描述',

    -- Task Coordinate（来自 log_task_started）
    task_agent       VARCHAR(128)  DEFAULT NULL COMMENT '分配的 worker role',

    -- 状态
    status           VARCHAR(16)   NOT NULL DEFAULT 'queued'
                       COMMENT 'queued / running / completed / failed',

    -- 展示内容
    content          TEXT          DEFAULT NULL,

    -- Plan 节点专用：分解结果
    plan_steps_json  JSON          DEFAULT NULL COMMENT 'plan 节点的 steps 数组',
    plan_task_count  SMALLINT      DEFAULT NULL COMMENT '分解出的子任务总数',

    -- 排序
    sort_order       INT           NOT NULL DEFAULT 0,

    -- 时序
    started_at       DATETIME      DEFAULT NULL,
    ended_at         DATETIME      DEFAULT NULL,
    processing_time_ms INT         DEFAULT NULL COMMENT 'CAMEL processing_time_seconds * 1000',

    created_at       DATETIME      NOT NULL,
    updated_at       DATETIME      NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_session_node  (session_id, node_id),
    KEY idx_session_order       (session_id, sort_order),
    KEY idx_session_status      (session_id, status),
    KEY idx_conv                (conversation_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'Verla Workforce 子任务状态快照（task decompose / coordinate / info）';


CREATE TABLE IF NOT EXISTS verla_workforce_task_outputs (
    id               BIGINT        NOT NULL AUTO_INCREMENT,

    -- Verla 三层上下文
    conversation_id  BIGINT        NOT NULL,
    turn_id          BIGINT        NOT NULL,
    session_id       BIGINT        NOT NULL,

    -- 关联（与 verla_workforce_tasks.node_id 对齐）
    node_id          VARCHAR(128)  NOT NULL,

    -- Task Output
    result_text      MEDIUMTEXT    DEFAULT NULL COMMENT '任务最终产出文本，来自 result_summary',

    -- 流式 detail
    detail_items_json JSON         DEFAULT NULL COMMENT 'detailChunk 数组累积：[{type, name}]',

    created_at       DATETIME      NOT NULL,
    updated_at       DATETIME      NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_session_node (session_id, node_id),
    KEY idx_session            (session_id),
    KEY idx_conv               (conversation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'Verla Workforce 子任务产出内容（task output / detail chunks）';
