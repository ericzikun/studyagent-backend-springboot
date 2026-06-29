-- ========================================
-- 迁移脚本 067: Verla Major Upgrade 更新公告
-- 创建日期: 2026-06-29
-- 说明: 插入 Verla 重大升级公告，供 Header 通知入口展示
-- 注意: test 环境 announcements 表无 content 列，正文写入 message
-- ========================================

INSERT INTO announcements (
    public_id,
    title,
    message,
    sort_order,
    is_active,
    publish_at
) VALUES (
    'notif-2026-06-29-verla-major-upgrade',
    'Verla Major Upgrade',
    'Verla has received a major upgrade.
This release introduces a refreshed interface, expanded Assignment capabilities, and more precise Detection and Humanizer workflows—giving you a clearer, smoother, and more powerful experience.

✨ A Brand-New UI
Verla''s interface and visual design have been fully refreshed.
We''ve improved the layout, information hierarchy, and user flows to make features easier to find and the overall experience more intuitive and seamless.

💻 Assignment Now Supports Coding and PPT
Verla Assignment now supports two new output types:
- Coding for programming assignments and code-based tasks
- PPT for presentations and slide-based projects
From written documents to code and presentations, Verla now supports a wider range of academic and creative workflows.

🚀 Better Assignment Results
We''ve improved the overall quality of Verla Assignment with:
- Higher-quality content
- More accurate citations
- Support for more document formats
The result is a more complete, reliable, and task-aligned experience.

🔍 Chunk-Level Scanning for Detection and Humanizer
Verla Detection and Humanizer now support chunk-level scanning and processing.
This makes it easier to analyze and refine long-form content or large documents with greater precision and clearer results.',
    300,
    1,
    NULL
)
ON DUPLICATE KEY UPDATE
    title       = VALUES(title),
    message     = VALUES(message),
    sort_order  = VALUES(sort_order),
    is_active   = VALUES(is_active),
    publish_at  = VALUES(publish_at);

-- 可选：下线旧的 Launch Deal 公告，避免与新公告并存
-- UPDATE announcements
-- SET is_active = 0, updated_at = NOW()
-- WHERE public_id = 'ann_launch_deal_early_pricing_202602';

SELECT '✅ Migration 067: Verla Major Upgrade announcement upserted' AS result;

-- 排查用：确认当前有效公告
-- SELECT public_id, title, sort_order, is_active, publish_at, expire_at
-- FROM announcements
-- ORDER BY sort_order DESC, id DESC;
