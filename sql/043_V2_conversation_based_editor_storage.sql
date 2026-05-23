-- ========================================
-- 迁移 042: Conversation 维度编辑器工作态存储
-- 创建日期: 2026-05-19
-- 说明:
--   - 新增 `verla_editor_contents`
--   - 新增 `verla_editor_content_versions`
--   - 以 `conversation_id + source_artifact_uid + editor_kind` 作为工作稿唯一维度
--   - 不替换现有 `task_editor_contents`，先并行存在，供 V2 Verla editor 接入
-- ========================================

USE studyagent;

-- ----------------------------------------
-- 1) 当前编辑器工作态
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_editor_contents (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id      BIGINT       NOT NULL                       COMMENT '所属对话',
    source_artifact_uid  VARCHAR(96)  NOT NULL                       COMMENT '用户当前编辑的业务产物 UID',
    seed_artifact_uid    VARCHAR(96)  DEFAULT NULL                   COMMENT '初始化编辑器内容所使用的种子 artifact UID',
    editor_kind          VARCHAR(32)  NOT NULL                       COMMENT 'document / slides / code',
    title                VARCHAR(255) DEFAULT NULL                   COMMENT '当前编辑标题',
    content_json         LONGTEXT     NOT NULL                       COMMENT '当前编辑器内容',
    meta_json            JSON         DEFAULT NULL                   COMMENT '编辑器扩展元数据',
    content_schema_version INT       NOT NULL DEFAULT 1             COMMENT '编辑器内容 schema 版本',
    created_by           VARCHAR(64)  DEFAULT NULL                   COMMENT 'clerk user id',
    updated_by           VARCHAR(64)  DEFAULT NULL                   COMMENT 'clerk user id',
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_source_kind (conversation_id, source_artifact_uid, editor_kind),
    KEY idx_conv_kind (conversation_id, editor_kind, updated_at),
    KEY idx_source_artifact_uid (source_artifact_uid),
    KEY idx_seed_artifact_uid (seed_artifact_uid),
    CONSTRAINT fk_verla_editor_contents_conversation
      FOREIGN KEY (conversation_id) REFERENCES verla_conversations(id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Conversation 维度编辑器当前工作态';

-- ----------------------------------------
-- 2) 编辑器历史版本
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_editor_content_versions (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    editor_content_id    BIGINT       NOT NULL                       COMMENT 'verla_editor_contents.id',
    version_no           INT          NOT NULL                       COMMENT '版本号，从 1 开始递增',
    content_json         LONGTEXT     NOT NULL                       COMMENT '该版本对应的编辑器内容',
    meta_json            JSON         DEFAULT NULL                   COMMENT '该版本对应的编辑器扩展元数据',
    save_source          VARCHAR(32)  NOT NULL DEFAULT 'manual_save' COMMENT 'imported / manual_save / autosave',
    created_by           VARCHAR(64)  DEFAULT NULL                   COMMENT 'clerk user id',
    created_at           DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_editor_content_version (editor_content_id, version_no),
    KEY idx_editor_content_created (editor_content_id, created_at),
    CONSTRAINT fk_verla_editor_content_versions_content
      FOREIGN KEY (editor_content_id) REFERENCES verla_editor_contents(id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Conversation 维度编辑器历史版本';

SELECT '042_V2_conversation_based_editor_storage applied' AS result;
