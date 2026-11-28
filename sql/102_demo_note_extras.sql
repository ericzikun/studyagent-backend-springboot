-- AI Note 三视图：导图 + 博客（与笔记正文同会话存储）
ALTER TABLE demo_note_conversation
    ADD COLUMN mindmap_json LONGTEXT NULL AFTER note_version,
    ADD COLUMN blog_md LONGTEXT NULL AFTER mindmap_json;
