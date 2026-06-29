-- ========================================
-- 迁移脚本 068: announcements 增加 icon_url
-- 创建日期: 2026-06-29
-- 说明: 与后端 AnnouncementEntity 对齐，修复 Unknown column 'icon_url' 报错
-- ========================================

ALTER TABLE announcements
    ADD COLUMN icon_url VARCHAR(512) NULL COMMENT '通知图标 URL' AFTER message;

SELECT '✅ Migration 068: announcements add icon_url' AS result;
