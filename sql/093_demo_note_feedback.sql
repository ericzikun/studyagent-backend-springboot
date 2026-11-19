-- 笔记编辑器顶栏的用户反馈（点赞 / 点踩 / 举报）。一条会话可有多条记录。
CREATE TABLE IF NOT EXISTS demo_note_feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    note_conversation_id BIGINT NOT NULL,
    clerk_user_id VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    comment TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_demo_note_feedback_conv (note_conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='笔记编辑器的点赞/点踩/举报';
