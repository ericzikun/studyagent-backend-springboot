-- =====================================================================
-- AI Tutor（学术论文写作 Copilot）demo 表 —— demo_ai_tutor_*
-- 与 verla_agent(services/ai_tutor) 通过 MQ 信封协议对接；业务投影以本库为准。
-- 纯新增表，不影响现有 081 及之前的任何表。
-- =====================================================================

CREATE TABLE IF NOT EXISTS demo_ai_tutor_conversation (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '会话ID',
    clerk_user_id VARCHAR(128) NOT NULL COMMENT 'Clerk 用户ID',
    title         VARCHAR(255) NULL COMMENT '会话/论文标题',
    initial_query VARCHAR(1024) NOT NULL COMMENT '初始目标',
    paper_meta    JSON NULL COMMENT '论文元信息：类型/字数/语言/要求',
    status        VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT 'active/archived',
    base_version  BIGINT NOT NULL DEFAULT 0 COMMENT '文档基线版本号',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_aitutor_conv_user (clerk_user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI Tutor 会话';

CREATE TABLE IF NOT EXISTS demo_ai_tutor_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
    conversation_id BIGINT NOT NULL COMMENT '会话ID',
    role            VARCHAR(16) NOT NULL COMMENT 'user/assistant/system',
    msg_type        VARCHAR(24) NOT NULL DEFAULT 'text' COMMENT 'text/interactive/material/artifact_event',
    content_md      MEDIUMTEXT NULL COMMENT 'Markdown 内容',
    seq             BIGINT NOT NULL COMMENT '会话内序号',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_aitutor_msg_conv (conversation_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI Tutor 消息';

CREATE TABLE IF NOT EXISTS demo_ai_tutor_document (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '文档ID',
    conversation_id BIGINT NOT NULL COMMENT '会话ID',
    title           VARCHAR(255) NULL COMMENT '论文标题',
    content_md      MEDIUMTEXT NULL COMMENT '论文 Markdown 全文',
    base_version    BIGINT NOT NULL DEFAULT 0 COMMENT '当前基线版本',
    updated_by      VARCHAR(16) NOT NULL DEFAULT 'ai' COMMENT '最近写入方 ai/user',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_aitutor_doc_conv (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI Tutor 论文活文档';

CREATE TABLE IF NOT EXISTS demo_ai_tutor_doc_version (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '版本ID',
    document_id BIGINT NOT NULL COMMENT '文档ID',
    version_no  BIGINT NOT NULL COMMENT '版本号',
    source      VARCHAR(16) NOT NULL COMMENT 'ai/user',
    content_md  MEDIUMTEXT NULL COMMENT '该版本全文快照',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_aitutor_doc_ver (document_id, version_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI Tutor 文档版本快照';

CREATE TABLE IF NOT EXISTS demo_ai_tutor_evidence (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '证据ID',
    conversation_id BIGINT NOT NULL COMMENT '会话ID',
    source_type     VARCHAR(16) NOT NULL DEFAULT 'user' COMMENT 'user=用户材料/search=实时检索',
    title           VARCHAR(512) NOT NULL COMMENT '文献标题',
    url             VARCHAR(1024) NULL COMMENT '来源链接',
    snippet         TEXT NULL COMMENT '摘要/正文片段',
    meta_json       JSON NULL COMMENT '扩展信息',
    seq_no          BIGINT NULL COMMENT '确认后的引用编号 n',
    confirmed       TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已确认引用',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_aitutor_ev_conv (conversation_id, confirmed)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI Tutor 引用证据';
