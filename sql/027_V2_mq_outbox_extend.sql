-- ========================================
-- 迁移脚本 027_V2: mq_outbox 扩展（兼容老链路 + 接入 Verla 信封）
-- 创建日期: 2026-04-25
-- 说明:
--   对应文档 docs/verla-Java侧MVP技术方案.md §4.2
--   - event_id 语义复用为 Verla 协议里的 messageId（不重命名以兼容旧 ORM）
--   - task_id 老链路继续填；Verla 命令置 NULL，靠 conv/turn/session/correlation 关联
--   - 新增 conv/turn/session 三层关联键 + correlationId / orderingKey / schemaVersion
-- ========================================

USE studyagent;

ALTER TABLE mq_outbox
    ADD COLUMN correlation_id  VARCHAR(160) DEFAULT NULL  COMMENT 'conv:{cid}:turn:{tid}:sess:{sid}'                        AFTER payload,
    ADD COLUMN ordering_key    VARCHAR(64)  DEFAULT NULL  COMMENT 'session:{sessionId}'                                      AFTER correlation_id,
    ADD COLUMN schema_version  INT          NOT NULL DEFAULT 1 COMMENT '信封 schema 版本'                                    AFTER ordering_key,
    ADD COLUMN conversation_id BIGINT       DEFAULT NULL  COMMENT 'Verla conversation id（老链路为 NULL）'                   AFTER schema_version,
    ADD COLUMN turn_id         BIGINT       DEFAULT NULL  COMMENT 'Verla turn id（老链路为 NULL）'                            AFTER conversation_id,
    ADD COLUMN session_id      BIGINT       DEFAULT NULL  COMMENT 'Verla session id（老链路为 NULL）'                         AFTER turn_id,
    ADD COLUMN exchange        VARCHAR(64)  DEFAULT NULL  COMMENT '目标 exchange（NULL 走默认）'                              AFTER session_id,
    ADD COLUMN routing_key     VARCHAR(160) DEFAULT NULL  COMMENT '路由键；与 action 不同，可独立指定'                        AFTER exchange,
    ADD INDEX idx_correlation (correlation_id),
    ADD INDEX idx_session     (session_id);
