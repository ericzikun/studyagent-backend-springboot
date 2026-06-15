-- V2 quota ledger allocations: record exact pool splits for consume/refund.

USE studyagent;

CREATE TABLE IF NOT EXISTS quota_ledger_allocations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    quota_ledger_id BIGINT NOT NULL,
    pool_type VARCHAR(16) NOT NULL COMMENT 'free / plan / addon / legacy',
    grant_id BIGINT NULL COMMENT 'user_addon_grants.id when pool_type=addon',
    amount BIGINT NOT NULL,
    source_period_end DATETIME NULL COMMENT 'Original pool or grant expiry snapshot used by refund logic',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_quota_allocations_ledger (quota_ledger_id),
    INDEX idx_quota_allocations_grant (grant_id),
    INDEX idx_quota_allocations_pool (pool_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Structured allocation rows for quota ledger consume/refund entries';
