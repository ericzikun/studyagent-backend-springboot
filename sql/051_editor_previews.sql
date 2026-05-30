-- ========================================
-- 迁移 050: Editor Preview 缩略图存储
-- 创建日期: 2026-05-30
-- 说明:
--   - 新增 `verla_editor_previews`
--   - 以 `conversation_id + source_artifact_uid + editor_kind` 作为唯一维度
--   - 独立于 verla_editor_contents，避免占位行问题
--   - 存储缩略图 OSS URL 和重复检测的 content_hash
-- ========================================

USE studyagent;

CREATE TABLE IF NOT EXISTS verla_editor_previews (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id     BIGINT       NOT NULL COMMENT '所属对话',
    source_artifact_uid VARCHAR(96)  NOT NULL COMMENT '业务产物 UID',
    editor_kind         VARCHAR(32)  NOT NULL COMMENT 'document / code / slides',
    attachment_object_id VARCHAR(96) DEFAULT NULL COMMENT 'verla_attachments.object_id',
    preview_url         VARCHAR(1024) NOT NULL COMMENT '可直接给前端使用的缩略图 URL',
    content_hash        VARCHAR(128) DEFAULT NULL COMMENT '用于避免重复上传的内容指纹',
    capture_source      VARCHAR(64)  DEFAULT NULL COMMENT 'result_background / fullscreen_editor / slides_bridge',
    width               INT          DEFAULT NULL,
    height              INT          DEFAULT NULL,
    created_by          VARCHAR(64)  DEFAULT NULL,
    updated_by          VARCHAR(64)  DEFAULT NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_artifact_kind (conversation_id, source_artifact_uid, editor_kind),
    KEY idx_conv_preview (conversation_id, updated_at),
    KEY idx_artifact_preview (source_artifact_uid),
    CONSTRAINT fk_verla_editor_previews_conversation
        FOREIGN KEY (conversation_id) REFERENCES verla_conversations(id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Conversation 维度 editor preview 元数据';
