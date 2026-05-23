USE studyagent;

SET @col_origin = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'verla_attachments'
      AND COLUMN_NAME = 'attachment_origin'
);

SET @sql_origin = IF(@col_origin = 0,
    'ALTER TABLE verla_attachments ADD COLUMN attachment_origin VARCHAR(32) NOT NULL DEFAULT ''USER_UPLOAD'' COMMENT ''USER_UPLOAD / AGENT_OUTPUT'' AFTER primary_artifact_uid',
    'SELECT ''column attachment_origin already exists'' AS info'
);
PREPARE stmt FROM @sql_origin;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
