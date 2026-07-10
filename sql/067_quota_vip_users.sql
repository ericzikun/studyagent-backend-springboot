-- Quota VIP: product experience unlimited (quota + entitlements), no admin console access.
-- Ops can INSERT/UPDATE rows; app reads with short local cache (no restart required).

USE studyagent;

CREATE TABLE IF NOT EXISTS quota_vip_users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    clerk_user_id   VARCHAR(255) NOT NULL COMMENT 'Clerk user id',
    status          VARCHAR(16)  NOT NULL DEFAULT 'active'
                    COMMENT 'active / disabled',
    note            VARCHAR(255) NULL COMMENT '备注：体验官/合作方等',
    expires_at      DATETIME     NULL COMMENT '为空表示长期有效',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quota_vip_clerk_user (clerk_user_id),
    INDEX idx_quota_vip_status_expires (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='额度/权益 VIP：跳过扣费且套餐权益无限，不含运营后台权限';

-- 开通示例：
-- INSERT INTO quota_vip_users (clerk_user_id, status, note)
-- VALUES ('user_xxx', 'active', '内测体验')
-- ON DUPLICATE KEY UPDATE status = 'active', note = VALUES(note), expires_at = NULL;
--
-- 关闭示例：
-- UPDATE quota_vip_users SET status = 'disabled' WHERE clerk_user_id = 'user_xxx';
