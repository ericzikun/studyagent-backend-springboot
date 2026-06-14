-- =============================================================================
-- 062 性能优化：遗留表索引 + verla_messages.scene 冗余列
-- 上线前执行；可重复执行（索引/列存在则跳过）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 1.0 遗留表联合索引
-- -----------------------------------------------------------------------------

-- tasks：用户任务列表按状态+时间排序
SET @idx_exists := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tasks'
      AND index_name = 'idx_clerk_user_status_created'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_clerk_user_status_created ON tasks (clerk_user_id, status, created_at)',
    'SELECT ''idx_clerk_user_status_created already exists'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 兼容旧列名 user_id（若 clerk_user_id 不存在则尝试 user_id）
SET @has_clerk := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'tasks'
      AND column_name = 'clerk_user_id'
);
SET @has_user := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'tasks'
      AND column_name = 'user_id'
);
SET @idx_user_exists := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tasks'
      AND index_name = 'idx_user_status_created'
);
SET @sql := IF(@has_clerk = 0 AND @has_user > 0 AND @idx_user_exists = 0,
    'CREATE INDEX idx_user_status_created ON tasks (user_id, status, created_at)',
    'SELECT ''skip tasks user_id index'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- task_activities：detail 接口取最近活动
SET @idx_exists := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'task_activities'
      AND index_name = 'idx_task_activity_time'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_task_activity_time ON task_activities (task_id, activity_time)',
    'SELECT ''idx_task_activity_time already exists'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- flow_events：事件回放（若表存在）
SET @tbl_exists := (
    SELECT COUNT(1) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'flow_events'
);
SET @idx_exists := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'flow_events'
      AND index_name = 'idx_session_event_seq'
);
SET @sql := IF(@tbl_exists > 0 AND @idx_exists = 0,
    'CREATE INDEX idx_session_event_seq ON flow_events (session_id, event_seq)',
    'SELECT ''skip flow_events index'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 2) verla_messages.scene 冗余列（替代 JSON_EXTRACT 过滤）
-- -----------------------------------------------------------------------------

SET @col_exists := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'verla_messages'
      AND column_name = 'scene'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE verla_messages ADD COLUMN scene VARCHAR(32) DEFAULT NULL COMMENT ''消息场景：FILE_CHAT / ASSIGNMENT_CHAT / 空=主对话'' AFTER meta_json',
    'SELECT ''verla_messages.scene already exists'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 回填历史数据
UPDATE verla_messages
SET scene = JSON_UNQUOTE(JSON_EXTRACT(meta_json, '$.scene'))
WHERE scene IS NULL
  AND meta_json IS NOT NULL
  AND JSON_EXTRACT(meta_json, '$.scene') IS NOT NULL;

SET @idx_exists := (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'verla_messages'
      AND index_name = 'idx_conv_scene_id'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_conv_scene_id ON verla_messages (conversation_id, scene, id)',
    'SELECT ''idx_conv_scene_id already exists'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
