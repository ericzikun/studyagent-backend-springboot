-- ========================================
-- 053: verla_conversations 增加 last_active_at（改动时间）
-- 创建日期: 2026-06-10
-- 说明:
--   - Recent Task 列表排序由 last_message_at 切换为 last_active_at（改动时间）。
--   - last_active_at 在以下用户行为发生时刷新：
--       1) 用户在任务页有新的点击（POST /v1/verla/conversations/{cid}/activity）
--       2) 编辑页内容更新（保存 editor-content）
--       3) 发送新消息 / 新 turn（沿用 touchOnNewTurn）
--   - 与 updated_at 区分：updated_at 受后台 version 自增、artifact 写入等内部动作影响，
--     不适合作为「用户可感知的改动时间」排序键。
-- ========================================

USE studyagent;

ALTER TABLE verla_conversations
    ADD COLUMN last_active_at DATETIME DEFAULT NULL
        COMMENT '改动时间：用户点击任务/编辑内容/发送消息时刷新（Recent Task 排序键）'
        AFTER last_message_at;

-- 历史数据回填：取已有最近活跃时间，避免老会话全部沉底
UPDATE verla_conversations
SET last_active_at = COALESCE(last_message_at, updated_at, created_at)
WHERE last_active_at IS NULL;

-- 排序索引（与 idx_user_status_lm 对应，用于按用户 + 状态 + 改动时间倒序分页）
ALTER TABLE verla_conversations
    ADD KEY idx_user_status_la (user_id, status, last_active_at);
