-- =============================================================================
-- 067 V2 Header 公告：表结构 + 非通用 icon URL
-- 可重复执行；既支持新环境建表，也支持已有 1.0 公告表补列。
-- =============================================================================

CREATE TABLE IF NOT EXISTS announcements (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT NULL,
    icon_url VARCHAR(512) NULL COMMENT '公告列表图标 URL；为空时前端展示默认公告图标',
    sort_order INT NOT NULL DEFAULT 0,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    publish_at DATETIME NULL,
    expire_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_announcements_public_id (public_id),
    KEY idx_announcements_active_window_sort (is_active, publish_at, expire_at, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_announcement_reads (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    clerk_user_id VARCHAR(128) NOT NULL,
    announcement_public_id VARCHAR(64) NOT NULL,
    read_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_announcement_read (clerk_user_id, announcement_public_id),
    KEY idx_user_announcement_reads_user (clerk_user_id, read_at),
    KEY idx_user_announcement_reads_announcement (announcement_public_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @col_exists := (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'announcements'
      AND column_name = 'icon_url'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE announcements ADD COLUMN icon_url VARCHAR(512) NULL COMMENT ''公告列表图标 URL；为空时前端展示默认公告图标''',
    'SELECT ''announcements.icon_url already exists'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
