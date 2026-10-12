-- =====================================================================
-- 090: 笔记回合消息表 —— demo_note_message
--
-- 能力升级：笔记从「单轮一次性生成」改成「多轮对话回合」——
--   首轮（带素材）解析 → 归纳 → 写整篇笔记；后续轮只对话，
--   agent 自行决定是否改文档（见 verla_agent app/services/note）。
--
-- 本表存每一轮的用户消息与助手回复，用途有二：
--   1) 左侧对话栏的消息列表（快照按 seq 升序返回）；
--   2) Java 派发后续轮时按「条数 + 字节」双预算裁剪出 history 下发给 Python。
--
-- 为何新表而不是塞进 demo_note_conversation：
--   消息随轮次无限增长，main 表只应保留「当前会话状态」，避免宽表膨胀。
--
-- 编号说明：release 分支已有别人的 089_aitutor_message_agent.sql，本仓 089 是
-- demo_note_fields；新表统一走 090，不再与 089 叠加。
--
-- 幂等性由调用方保证（CREATE TABLE IF NOT EXISTS）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS demo_note_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
    conversation_id BIGINT NOT NULL COMMENT 'demo_note_conversation.id',
    role            VARCHAR(16) NOT NULL COMMENT 'user/assistant',
    msg_type        VARCHAR(24) NOT NULL DEFAULT 'text' COMMENT 'text（预留 artifact/system）',
    content_md      MEDIUMTEXT NOT NULL COMMENT '消息正文（Markdown）',
    seq             INT NOT NULL COMMENT '会话内递增序号，从 1 开始',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_note_msg_seq (conversation_id, seq),
    KEY idx_note_msg_conv (conversation_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '笔记回合消息（对话栏 + history 裁剪源）';
