-- Ops internal team users: used by Verla Ops console to optionally exclude
-- internal UIDs from /admin/conversations listing.

USE studyagent;

CREATE TABLE IF NOT EXISTS ops_internal_users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    clerk_user_id   VARCHAR(255) NOT NULL COMMENT 'Clerk user id（团队内部账号）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'active'
                    COMMENT 'active / disabled',
    note            VARCHAR(255) NULL COMMENT '备注：研发/运营/测试等',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ops_internal_clerk_user (clerk_user_id),
    INDEX idx_ops_internal_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='运维控制台：团队内部用户名单（用于过滤对话列表）';

-- 用户画像国家字段（运维展示；可由人工维护或后续注册 IP 回填）
-- 若列已存在则跳过。
SET @ops_country_col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_profiles'
      AND COLUMN_NAME = 'country'
);
SET @ops_country_ddl := IF(
    @ops_country_col_exists = 0,
    'ALTER TABLE user_profiles ADD COLUMN country VARCHAR(64) NULL COMMENT ''国家/地区（运维画像）'' AFTER locale',
    'SELECT 1'
);
PREPARE ops_country_stmt FROM @ops_country_ddl;
EXECUTE ops_country_stmt;
DEALLOCATE PREPARE ops_country_stmt;

-- 开通示例：
-- INSERT INTO ops_internal_users (clerk_user_id, status, note)
-- VALUES ('user_xxx', 'active', '内部研发')
-- ON DUPLICATE KEY UPDATE status = 'active', note = VALUES(note);
--
-- 关闭示例：
-- UPDATE ops_internal_users SET status = 'disabled' WHERE clerk_user_id = 'user_xxx';
--
-- 国家回填示例：
-- UPDATE user_profiles SET country = 'United States' WHERE clerk_user_id = 'user_xxx';
