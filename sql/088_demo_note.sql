-- =====================================================================
-- 088: 笔记生成 demo 表 —— demo_note_conversation
--
-- 能力：上传文件或粘贴文本 → 复用文件解析 → 单轮 LLM 归纳 → 生成 Markdown 笔记。
-- 与 verla_agent(app/services/note) 通过 MQ 信封协议对接；业务投影以本库为准。
-- 纯新增表，不影响现有表。
--
-- 每个 demo 会话对应一行主线 verla_conversations（primary_intent='NOTE_MAKER'）：
--   · VerlaSseController 用 conversationService.getOwned() 校验归属，前端订阅
--     GET /v1/verla/conversations/{vc_xxx}/events 必须以真实 cid 为键；
--   · 事件回填按 verla_conversation_id 反查本表（事件里没有 demo 主键）。
--
-- 幂等性由调用方保证（CREATE TABLE IF NOT EXISTS）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS demo_note_conversation (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '会话ID',
    clerk_user_id          VARCHAR(128) NOT NULL COMMENT 'Clerk 用户ID',
    verla_conversation_id  BIGINT DEFAULT NULL COMMENT '主线 verla_conversations.id，SSE 事件通道与会话归属校验的键',
    title                  VARCHAR(255) NULL COMMENT '笔记标题（生成前为文件名/正文片段占位）',
    source_type            VARCHAR(16) NULL COMMENT '素材类型 file/text，未提交时为 NULL',
    attachment_object_id   VARCHAR(128) NULL COMMENT 'sourceType=file 时的附件 objectId',
    input_text             MEDIUMTEXT NULL COMMENT 'sourceType=text 时粘贴的原始文本',
    note_md                MEDIUMTEXT NULL COMMENT '生成的笔记 Markdown 全文',
    status                 VARCHAR(24) NOT NULL DEFAULT 'draft' COMMENT 'draft/generating/completed/failed',
    created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_note_conv_user (clerk_user_id, id),
    UNIQUE KEY uk_note_verla_conv (verla_conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '笔记生成 demo 会话';
