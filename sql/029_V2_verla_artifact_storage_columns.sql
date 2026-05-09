-- V2 Verla artifacts: align older live schemas with the current artifact model.
-- 026_V2_verla_schema.sql creates the full table only when it does not already
-- exist. Existing databases therefore need this additive migration.

SET @schema_name = DATABASE();

SET @add_artifact_uid = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN artifact_uid VARCHAR(96) DEFAULT NULL COMMENT ''V2 business artifact uid''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'artifact_uid'
);
PREPARE stmt FROM @add_artifact_uid;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_turn_id = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN turn_id BIGINT DEFAULT NULL COMMENT ''V2 source turn id''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'turn_id'
);
PREPARE stmt FROM @add_turn_id;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_session_id = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN session_id BIGINT DEFAULT NULL COMMENT ''V2 source session id''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'session_id'
);
PREPARE stmt FROM @add_session_id;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_source_message_id = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN source_message_id BIGINT DEFAULT NULL COMMENT ''V2 source message id''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'source_message_id'
);
PREPARE stmt FROM @add_source_message_id;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_source_object_id = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN source_object_id VARCHAR(64) DEFAULT NULL COMMENT ''V2 source attachment objectId''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'source_object_id'
);
PREPARE stmt FROM @add_source_object_id;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_kind = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN kind VARCHAR(32) DEFAULT NULL COMMENT ''V2 artifact kind''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'kind'
);
PREPARE stmt FROM @add_kind;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_mime = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN mime VARCHAR(64) DEFAULT NULL COMMENT ''V2 artifact mime type''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'mime'
);
PREPARE stmt FROM @add_mime;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_summary = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN summary VARCHAR(1024) DEFAULT NULL COMMENT ''V2 artifact summary''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'summary'
);
PREPARE stmt FROM @add_summary;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_content_ref = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN content_ref VARCHAR(255) DEFAULT NULL COMMENT ''V2 content reference''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'content_ref'
);
PREPARE stmt FROM @add_content_ref;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_body_or_ref = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN body_or_ref LONGTEXT DEFAULT NULL COMMENT ''V2 inline artifact body''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'body_or_ref'
);
PREPARE stmt FROM @add_body_or_ref;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_status = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''READY'' COMMENT ''V2 artifact status''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'status'
);
PREPARE stmt FROM @add_status;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_size_bytes = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN size_bytes BIGINT DEFAULT NULL COMMENT ''V2 artifact byte size''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'size_bytes'
);
PREPARE stmt FROM @add_size_bytes;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_version = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN version INT NOT NULL DEFAULT 1 COMMENT ''V2 artifact version''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'version'
);
PREPARE stmt FROM @add_version;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_meta_json = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN meta_json JSON DEFAULT NULL COMMENT ''V2 artifact metadata''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'meta_json'
);
PREPARE stmt FROM @add_meta_json;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_updated_at = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT ''V2 artifact updated time''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND column_name = 'updated_at'
);
PREPARE stmt FROM @add_updated_at;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_uk_artifact_uid = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD UNIQUE KEY uk_artifact_uid (artifact_uid)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND index_name = 'uk_artifact_uid'
);
PREPARE stmt FROM @add_uk_artifact_uid;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_idx_source_message = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD INDEX idx_source_message (source_message_id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND index_name = 'idx_source_message'
);
PREPARE stmt FROM @add_idx_source_message;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_idx_source_object = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE verla_artifacts ADD INDEX idx_source_object (source_object_id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'verla_artifacts'
      AND index_name = 'idx_source_object'
);
PREPARE stmt FROM @add_idx_source_object;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
