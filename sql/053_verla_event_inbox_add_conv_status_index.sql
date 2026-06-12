-- 054_verla_event_inbox_add_conv_status_index.sql
-- Dashboard 状态解析新增 selectRecentProcessed 查询：
--   WHERE conversation_id = ? AND status = 'PROCESSED' ORDER BY id DESC LIMIT ?
-- 原索引 idx_conv (conversation_id, received_at) 无法覆盖 ORDER BY id，
-- 触发 filesort 携带 payload_json 大字段，sort buffer 溢出报 MySQL 1038
-- （Out of sort memory）。
-- 新增 (conversation_id, status, id) 复合索引后两个等值条件 + id 排序均走索引，
-- 消除 filesort；selectReplay（conversation_id + id > ? + status，按 id 升序）同样受益。

ALTER TABLE verla_event_inbox
    ADD INDEX idx_conv_status_id (conversation_id, status, id);
