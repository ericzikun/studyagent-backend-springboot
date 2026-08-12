-- ========================================
-- 迁移脚本 079: V2 Humanizer 结果指纹/全文索引
-- 创建日期: 2026-08-12
-- 说明:
--   Detection 粘贴匹配用：同用户近 N 条 Humanizer 全文 + result_hash 快路径。
--   由 Verla artifact(kind=humanizer_result) 落库时写入。
-- ========================================

CREATE TABLE IF NOT EXISTS ai_writing_humanizer_results (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    clerk_user_id     VARCHAR(255) NOT NULL COMMENT 'Clerk user id',
    conversation_id   BIGINT       DEFAULT NULL,
    session_id        BIGINT       DEFAULT NULL,
    artifact_uid      VARCHAR(96)  NOT NULL COMMENT 'verla_artifacts.artifact_uid',
    result_hash       VARCHAR(64)  NOT NULL COMMENT 'SHA-256 of normalize(result_text)[:200]',
    result_text       MEDIUMTEXT   NOT NULL COMMENT 'Full humanizer output for fuzzy match',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_writing_humanizer_artifact (artifact_uid),
    KEY idx_ai_writing_humanizer_user_hash (clerk_user_id, result_hash),
    KEY idx_ai_writing_humanizer_user_created (clerk_user_id, created_at)
);

SELECT '✅ Migration 079: ai_writing_humanizer_results created' AS result;
