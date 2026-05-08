-- V2 Verla attachments: align DB schema with Java entity/service fields.
-- The Dashboard upload flow can sign files before a turn/session exists, so
-- session_id remains nullable. oss_key stores the stable object key used by
-- Java upload and Python parse consumers.

SET @schema_name = DATABASE();

SET @add_session_id = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_attachments ADD COLUMN session_id BIGINT DEFAULT NULL COMMENT ''可空：上传时所处 session，Dashboard 预上传阶段为 NULL'' AFTER turn_id',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_attachments'
      AND column_name = 'session_id'
);
PREPARE stmt FROM @add_session_id;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_oss_key = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_attachments ADD COLUMN oss_key VARCHAR(512) DEFAULT NULL COMMENT ''OSS/local object key for V2 attachment bytes'' AFTER storage_uri',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_attachments'
      AND column_name = 'oss_key'
);
PREPARE stmt FROM @add_oss_key;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_idx_session = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_attachments ADD INDEX idx_session (session_id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'verla_attachments'
      AND index_name = 'idx_session'
);
PREPARE stmt FROM @add_idx_session;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
