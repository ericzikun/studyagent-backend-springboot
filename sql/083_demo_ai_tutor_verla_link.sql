-- ============================================================================
-- 083: demo_ai_tutor_conversation 关联主线 verla_conversations
--
-- AI Tutor demo 此前自建 SSE 通道与私有事件队列，绕开了 Verla 主线的
-- mq_outbox（命令持久化/重试/publisher confirm）、verla_event_inbox（幂等）、
-- verla_event_cursor（保序）与 SSE Last-Event-ID 重放。改为并入主线后，
-- 每个 demo 会话需要在 verla_conversations 里有一个真实行：
--   · VerlaSseController 用 conversationService.getOwned(userId, cid) 校验归属，
--     前端订阅 GET /v1/verla/conversations/{vc_xxx}/events 必须以真实 cid 为键；
--   · VerlaInboxService.validate 只校验信封字段非空、不查外键存在性，
--     所以 turn / session 也建真行是为了 uk_correlation 排障与后续 quota 记账列。
--
-- 本列即 demo 会话 → 主线会话的 1:1 映射键。
--
-- 幂等性由调用方保证（MySQL 不支持 ADD COLUMN IF NOT EXISTS）：
-- start-mock.sh 用 column_exists 守卫后才 apply 本文件，与 048 / 049 同约定。
-- ============================================================================

ALTER TABLE demo_ai_tutor_conversation
    ADD COLUMN verla_conversation_id BIGINT DEFAULT NULL
        COMMENT '主线 verla_conversations.id，SSE 事件通道与会话归属校验的键；未接入主线时为 NULL'
        AFTER clerk_user_id;

ALTER TABLE demo_ai_tutor_conversation
    ADD UNIQUE KEY uk_aitutor_verla_conv (verla_conversation_id);
