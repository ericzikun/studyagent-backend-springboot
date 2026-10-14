-- =====================================================================
-- 089: demo_note_conversation 补字段
--
-- 1) course_name / learning_goal —— 上传表单新增的「课程名称」「学习目标」，
--    随命令 payload 下发给 Python 以调整笔记侧重。
-- 2) note_version —— 笔记版本号，供右侧编辑器「可编辑 + 自动保存」做乐观锁：
--    用户 PATCH 带 baseVersion，不匹配即 PARAM_ERROR（与 AI Tutor 双向基线同语义）。
--
-- 幂等：MySQL 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 守卫后再执行
-- （与 048/049/083 同约定）。
-- =====================================================================

SET @col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_note_conversation'
      AND COLUMN_NAME = 'course_name'
);
SET @sql := IF(@col = 0,
    'ALTER TABLE demo_note_conversation
        ADD COLUMN course_name VARCHAR(255) NULL COMMENT ''表单：课程名称'' AFTER input_text,
        ADD COLUMN learning_goal VARCHAR(64) NULL COMMENT ''表单：学习目标枚举码'' AFTER course_name,
        ADD COLUMN note_version BIGINT NOT NULL DEFAULT 0 COMMENT ''笔记版本号（AI/用户各+1，用户保存做乐观锁）'' AFTER note_md',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
