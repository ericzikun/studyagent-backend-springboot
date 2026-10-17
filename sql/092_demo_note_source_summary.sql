-- =====================================================================
-- 092: demo_note_conversation 补「首轮表单」字段
--
-- 左栏第一条气泡要把提交表单的内容整份展示出来（对齐作业链路的提交摘要卡片），
-- 而这几个值此前没落库，刷新后就还原不出来：
--   · attachment_filename —— 上传附件的原始文件名。title 会被 AI 起的会话名覆盖，
--     光靠 attachment_object_id 在前端拿不到名字，也就画不出「文件 + 类型图标」。
--   · output_language —— 表单选的笔记输出语言码。此前只随命令下发给 Python，没存。
--
-- 幂等：MySQL 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 守卫后再执行
-- （与 048/049/083/089/091 同约定）。
-- ⚠️ 若客户端会把语句拆到不同连接执行（用户变量丢失），守卫会报 `near 'NULL'`，
--    此时直接执行文件末尾那段裸 ALTER 即可。
-- =====================================================================

SET @col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_note_conversation'
      AND COLUMN_NAME = 'attachment_filename'
);
SET @sql := IF(@col = 0,
    'ALTER TABLE demo_note_conversation
        ADD COLUMN attachment_filename VARCHAR(255) NULL
        COMMENT ''表单：上传附件的原始文件名（AI 改名后 title 不再保留它）'' AFTER attachment_object_id,
        ADD COLUMN output_language VARCHAR(32) NULL
        COMMENT ''表单：笔记输出语言码'' AFTER learning_goal',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 守卫脚本跑不通时用这段裸 ALTER（重复执行报 Duplicate column 即已加过）
-- ---------------------------------------------------------------------
-- ALTER TABLE demo_note_conversation
--   ADD COLUMN attachment_filename VARCHAR(255) NULL
--     COMMENT '表单：上传附件的原始文件名（AI 改名后 title 不再保留它）' AFTER attachment_object_id,
--   ADD COLUMN output_language VARCHAR(32) NULL
--     COMMENT '表单：笔记输出语言码' AFTER learning_goal;
