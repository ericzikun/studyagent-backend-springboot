-- ============================================================================
-- 088: demo_ai_tutor_message 增加段落归属列 agent
--
-- AITUTOR_TURN_COMPLETED 的 payload 开始携带 segments=[{agent,text}]：
-- 一轮回复实际由多个 Agent 接力产出（mentor 讲解 → 主 Agent 收尾等），
-- 此前整轮拼成一条 blob 落库，前端无法区分「这段话是谁说的」。
-- Python 侧 chat 增量本就逐帧带 agent（AITUTOR_CHAT_STREAM_CHUNK.agent），
-- Java 在终态按段落库：每段一行，agent 记录产出者（mentor/outline/writer/main…）。
--
-- 兼容：
--   · 历史 msg 行 agent 为 NULL，前端对 NULL 不渲染归属标签（等同旧表现）；
--   · 无 segments 的终态（旧版 Python / 失败轮）仍退化为整条 blob 单行。
-- ============================================================================

ALTER TABLE demo_ai_tutor_message
    ADD COLUMN agent VARCHAR(32) NULL COMMENT '产出该段的 Agent（mentor/outline/writer/main…），NULL=无归属（历史消息/用户消息）' AFTER msg_type;
