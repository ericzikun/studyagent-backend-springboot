-- ========================================
-- 042: verla_conversations 增加 intent_lifecycle
-- 说明:
--   - none: 未进入「待确认意图」或已清空（拒识 / something else 等）
--   - awaiting_user_confirmation: Plan 已解析并展示确认卡，用户尚未确认
--   - committed: 用户已确认意图，或 forceIntent 等不经确认卡的路径
-- ========================================

USE studyagent;

ALTER TABLE verla_conversations
    ADD COLUMN intent_lifecycle VARCHAR(32) NOT NULL DEFAULT 'none'
        COMMENT 'none / awaiting_user_confirmation / committed'
        AFTER primary_intent;

-- 历史数据回填（MySQL 5.7+ JSON 函数）
-- 1) 已有「确认」用户消息的会话 → committed
UPDATE verla_conversations c
    INNER JOIN (
        SELECT DISTINCT conversation_id
        FROM verla_messages
        WHERE role = 'user'
          AND JSON_UNQUOTE(JSON_EXTRACT(blocks_json, '$.phase')) = 'plan_confirmation'
          AND JSON_UNQUOTE(JSON_EXTRACT(blocks_json, '$.choice')) = 'confirmed'
    ) sub ON sub.conversation_id = c.id
SET c.intent_lifecycle = 'committed'
WHERE c.intent_lifecycle = 'none';

-- 2) 有 plan_intent 助手消息、且尚无确认记录 → awaiting
UPDATE verla_conversations c
SET c.intent_lifecycle = 'awaiting_user_confirmation'
WHERE c.primary_intent IS NOT NULL
  AND c.intent_lifecycle = 'none'
  AND EXISTS (
        SELECT 1
        FROM verla_messages m
        WHERE m.conversation_id = c.id
          AND m.role = 'assistant'
          AND JSON_UNQUOTE(JSON_EXTRACT(m.blocks_json, '$.phase')) = 'plan_intent'
    )
  AND NOT EXISTS (
        SELECT 1
        FROM verla_messages m
        WHERE m.conversation_id = c.id
          AND m.role = 'user'
          AND JSON_UNQUOTE(JSON_EXTRACT(m.blocks_json, '$.phase')) = 'plan_confirmation'
          AND JSON_UNQUOTE(JSON_EXTRACT(m.blocks_json, '$.choice')) = 'confirmed'
    );

-- 3) forceIntent 等跳过 plan 的路径：有主意图、无 plan session 的 turn 且已带 agent
UPDATE verla_conversations c
SET c.intent_lifecycle = 'committed'
WHERE c.primary_intent IS NOT NULL
  AND c.intent_lifecycle = 'none'
  AND EXISTS (
        SELECT 1
        FROM verla_turns t
        WHERE t.conversation_id = c.id
          AND t.plan_session_id IS NULL
          AND t.agent_session_id IS NOT NULL
    );
