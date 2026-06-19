CREATE TABLE IF NOT EXISTS payment_resume_context (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    resume_token VARCHAR(64) NOT NULL,
    clerk_user_id VARCHAR(128) NOT NULL,
    scene VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NULL,
    idempotency_key VARCHAR(128) NULL,
    payload_json TEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    expires_at DATETIME NOT NULL,
    resumed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_payment_resume_context_token (resume_token),
    KEY idx_payment_resume_context_user_status_expires (clerk_user_id, status, expires_at),
    KEY idx_payment_resume_context_status_expires (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
