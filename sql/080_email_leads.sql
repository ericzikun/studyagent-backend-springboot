-- 公开页面邮箱留资：仅保存规范化邮箱、首次来源和创建时间。
-- 发送、订阅状态、consent_version 等营销字段不属于当前 MVP。
CREATE TABLE IF NOT EXISTS email_leads (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    email_normalized VARCHAR(254) NOT NULL,
    source_path VARCHAR(191) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_email_leads_email_normalized (email_normalized)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
