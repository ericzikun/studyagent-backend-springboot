-- AI Note 四视图：四选一测验（与笔记同会话存储）
ALTER TABLE demo_note_conversation
    ADD COLUMN quiz_json LONGTEXT NULL AFTER blog_md;
