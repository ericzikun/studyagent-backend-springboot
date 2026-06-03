-- 048_verla_editor_assets.sql
-- 编辑器内部素材资源域，与 verla_attachments 独立
-- 只服务编辑器渲染/保存/恢复，不参与作业附件语义

CREATE TABLE IF NOT EXISTS verla_editor_assets (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    asset_id          VARCHAR(64)  NOT NULL               COMMENT '业务唯一 ID（ea_*）',
    conversation_id   BIGINT       NOT NULL,
    artifact_uid      VARCHAR(96)  DEFAULT NULL           COMMENT '所属 artifact UID',
    editor_kind       VARCHAR(16)  NOT NULL               COMMENT 'document / slides / code',
    asset_role        VARCHAR(24)  NOT NULL               COMMENT 'inline_image / slide_image / slide_background / editor_file',
    user_id           VARCHAR(64)  NOT NULL               COMMENT 'clerkUserId',
    filename          VARCHAR(255) NOT NULL,
    mime              VARCHAR(96)  NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    storage_uri       VARCHAR(512) DEFAULT NULL,
    oss_key           VARCHAR(512) DEFAULT NULL,
    checksum_sha256   VARCHAR(64)  DEFAULT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / UPLOADED / FINALIZED',
    meta_json         JSON         DEFAULT NULL,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_asset_id (asset_id),
    KEY idx_conv (conversation_id),
    KEY idx_artifact (artifact_uid),
    KEY idx_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT '编辑器内部素材资源域';
