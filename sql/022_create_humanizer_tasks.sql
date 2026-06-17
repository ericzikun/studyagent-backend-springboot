-- ========================================
-- 迁移脚本 022: 创建 humanizer_tasks 异步任务队列表
-- 创建日期: 2026-03-02
-- 说明:
--   - AI检测(DETECT)和文本改写(HUMANIZE)共用一张表
--   - 用户提交后立即入库排队，后台异步处理
--   - sentences_json 存储逐句检测结果，后台每跑完一句就更新，前端轮询可看到进度
-- ========================================

USE studyagent;

CREATE TABLE IF NOT EXISTS humanizer_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Primary key',
    clerk_user_id VARCHAR(255) NOT NULL COMMENT 'Clerk user ID',

    -- Type: DETECT / HUMANIZE
    task_type VARCHAR(20) NOT NULL COMMENT 'Task type: DETECT or HUMANIZE',

    -- Input
    input_text MEDIUMTEXT NOT NULL COMMENT 'Input text',

    -- Status: CHARGING / PENDING / PROCESSING / COMPLETED / FAILED / QUOTA_EXHAUSTED / CANCELLED
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'Task status',

    -- Detect results
    probability DOUBLE NULL COMMENT 'Overall AI probability (0~1), DETECT only',
    label VARCHAR(50) NULL COMMENT 'AI Generated / Human Written, DETECT only',
    sentences_json MEDIUMTEXT NULL COMMENT 'Per-sentence detect results JSON array, updated incrementally',
    total_sentences INT NULL COMMENT 'Total sentence count, known after first chunk',
    completed_sentences INT NOT NULL DEFAULT 0 COMMENT 'Number of sentences completed so far',

    -- Humanize results
    result_text MEDIUMTEXT NULL COMMENT 'Rewritten text, HUMANIZE only',

    -- Common
    elapsed_seconds DOUBLE NULL COMMENT 'Processing time in seconds',
    error_message VARCHAR(1000) NULL COMMENT 'Error message if failed',
    retry_count INT NOT NULL DEFAULT 0 COMMENT 'Retry count',

    -- Timestamps
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    started_at DATETIME NULL COMMENT 'Processing started time',
    finished_at DATETIME NULL COMMENT 'Processing finished time',

    INDEX idx_clerk_user_id (clerk_user_id),
    INDEX idx_status_created (status, created_at),
    INDEX idx_clerk_type_status (clerk_user_id, task_type, status),
    INDEX idx_type_status_created (task_type, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Humanizer/AI-detect async task queue';

-- Verify
SELECT
    COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
AND TABLE_NAME = 'humanizer_tasks'
ORDER BY ORDINAL_POSITION;

SELECT '✅ Migration 022: humanizer_tasks table created' AS result;
