-- verla_attachments：解析产物缓存（与 docker-aliyun-20260125/migrations/041 同步）
-- 详见 migrations 文件内注释。

USE studyagent;

SET @col_md = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'verla_attachments'
      AND COLUMN_NAME = 'markdown_content'
);

SET @sql_md = IF(@col_md = 0,
    'ALTER TABLE verla_attachments ADD COLUMN markdown_content MEDIUMTEXT NULL COMMENT ''Py 解析全文缓存（对齐 files.markdown_content）'' AFTER meta_json',
    'SELECT ''column markdown_content already exists'' AS info'
);
PREPARE stmt FROM @sql_md;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_img = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'verla_attachments'
      AND COLUMN_NAME = 'images_json'
);

SET @sql_img = IF(@col_img = 0,
    'ALTER TABLE verla_attachments ADD COLUMN images_json TEXT NULL COMMENT ''抽取图片元数据 JSON（对齐 files.images_json）'' AFTER markdown_content',
    'SELECT ''column images_json already exists'' AS info'
);
PREPARE stmt FROM @sql_img;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
