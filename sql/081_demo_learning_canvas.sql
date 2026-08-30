-- ========================================
-- 迁移脚本 081: Learning Canvas Demo 数据表
-- 创建日期: 2026-08-30
-- 说明:
--   新 Demo 产品「Learning Canvas」（feature_code=demo_learning_canvas）。
--   只新增表，不 ALTER 任何旧表。表名前缀 demo_learning_*。
--   用户维度表统一含 clerk_user_id + created_at + 联合索引。
--   额度登记：INSERT ai_feature_defs（纯免费 + 每次调用记 quota_ledger），不改旧行。
-- ========================================

USE studyagent;

-- 1) 学习主题（对应 demo 的 themes）
CREATE TABLE IF NOT EXISTS demo_learning_theme (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    clerk_user_id    VARCHAR(255) NOT NULL COMMENT 'Clerk user id',
    initial_query    TEXT         NOT NULL COMMENT '用户开场 query',
    title            VARCHAR(500) DEFAULT NULL COMMENT '主题标题（建图后回填）',
    persona          VARCHAR(32)  NOT NULL DEFAULT 'sheldon' COMMENT '人格：sheldon/lasso',
    status           VARCHAR(32)  NOT NULL DEFAULT 'in_progress' COMMENT 'in_progress/completed',
    last_saved_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近自动保存时间（历史页排序）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     DEFAULT NULL,
    KEY idx_demo_learning_theme_user (clerk_user_id, last_saved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Learning Canvas 学习主题';

-- 2) 知识节点（对应 demo 的 knowledge_nodes）
CREATE TABLE IF NOT EXISTS demo_learning_node (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    theme_id         BIGINT       NOT NULL COMMENT 'demo_learning_theme.id',
    parent_id        BIGINT       DEFAULT NULL COMMENT '父节点 id（树形）',
    node_type        VARCHAR(32)  NOT NULL DEFAULT 'knowledge' COMMENT 'knowledge/dialogue_step/evidence/quiz/survey/compare/animation/sandbox/image_asset',
    title            VARCHAR(500) NOT NULL COMMENT '节点标题',
    summary          TEXT         DEFAULT NULL COMMENT '一句话概括/理解切片',
    mastery_level    VARCHAR(16)  NOT NULL DEFAULT '生疏' COMMENT '生疏/理解/熟练',
    learning_type    VARCHAR(16)  DEFAULT 'theory' COMMENT 'theory/practice/mixed',
    certainty_status VARCHAR(16)  DEFAULT 'confirmed' COMMENT 'confirmed/tentative',
    start_msg_id     VARCHAR(96)  DEFAULT NULL COMMENT '起始消息 id（demo 的 message id）',
    trajectory       TEXT         DEFAULT NULL COMMENT '学习轨迹 JSON（milestones）',
    pre_test_results TEXT         DEFAULT NULL COMMENT 'pre-test 题目 JSON（post-test 复用）',
    meta_json        TEXT         DEFAULT NULL COMMENT '扩展字段（组件配置等）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     DEFAULT NULL,
    KEY idx_demo_learning_node_theme (theme_id),
    KEY idx_demo_learning_node_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Learning Canvas 知识节点';

-- 3) 画布边（对应 demo 的 edge_connections）
CREATE TABLE IF NOT EXISTS demo_learning_edge (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    theme_id   BIGINT       NOT NULL COMMENT 'demo_learning_theme.id',
    source_id  BIGINT       NOT NULL COMMENT 'demo_learning_node.id',
    target_id  BIGINT       NOT NULL COMMENT 'demo_learning_node.id',
    label      VARCHAR(255) DEFAULT NULL COMMENT '边标签（如 前置/包含）',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     DEFAULT NULL,
    KEY idx_demo_learning_edge_theme (theme_id),
    KEY idx_demo_learning_edge_source (source_id),
    KEY idx_demo_learning_edge_target (target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Learning Canvas 画布边';

-- 4) 消息（对应 demo 的 messages；含内部消息与工具调用 JSON）
CREATE TABLE IF NOT EXISTS demo_learning_message (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    theme_id   BIGINT       NOT NULL COMMENT 'demo_learning_theme.id',
    role       VARCHAR(16)  NOT NULL COMMENT 'user/assistant/tool/system',
    content    MEDIUMTEXT   NOT NULL COMMENT '文本内容，或 tool_calls/tool 结果 JSON',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     DEFAULT NULL,
    KEY idx_demo_learning_message_theme (theme_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Learning Canvas 对话消息';

-- 5) Agent 状态（对应 demo 的 agent_state）
CREATE TABLE IF NOT EXISTS demo_learning_agent_state (
    theme_id                 BIGINT PRIMARY KEY COMMENT 'demo_learning_theme.id',
    current_focus_node_id    BIGINT       DEFAULT NULL COMMENT '当前焦点节点',
    pending_outline          TEXT         DEFAULT NULL COMMENT '待讲队列 JSON',
    current_learning_stage   VARCHAR(64)  DEFAULT NULL COMMENT 'pre_test/socratic_guiding/post_test/crystallization/apply/...',
    updated_at               DATETIME     DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Learning Canvas Agent 状态';

-- 6) 用户偏好（对应 demo 的 user_profiles；跨主题 L0 记忆）
CREATE TABLE IF NOT EXISTS demo_learning_user_profile (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    clerk_user_id VARCHAR(255) NOT NULL COMMENT 'Clerk user id',
    preferences  TEXT         DEFAULT NULL COMMENT '偏好 JSON 数组',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     DEFAULT NULL,
    UNIQUE KEY uk_demo_learning_profile_user (clerk_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Learning Canvas 跨主题用户偏好';

-- 7) 额度登记（只 INSERT，不改旧行；纯免费 + 每次调用记 quota_ledger）
INSERT INTO ai_feature_defs (feature_code, feature_name, quota_unit, free_quota_period, free_quota_amount, is_active, display_order, created_at, updated_at)
SELECT 'demo_learning_canvas', 'Learning Canvas', 'count', 'monthly', 0, 1,
       (SELECT COALESCE(MAX(display_order), 0) + 1 FROM ai_feature_defs), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ai_feature_defs WHERE feature_code = 'demo_learning_canvas');

SELECT '✅ Migration 081: demo_learning_* tables created' AS result;
