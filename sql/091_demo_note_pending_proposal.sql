-- =====================================================================
-- 091: demo_note_conversation 增加「待确认提案」列
--
-- 后续轮 agent 改笔记不再直接落库：Python 只发 hunk（改前/改后 + 上下文），
-- 用户在前端编辑器里逐块「接受/忽略」后，才由
-- POST /v1/demo/note/conversations/{id}/proposal/commit 写 note_md 并推进版本。
--
-- 这一列就是那份待审提案（JSON）。落库而不是只放前端内存：
-- 刷新页面 / 换标签页回来仍能继续审阅，不会出现「agent 说改了但方案丢了」。
-- 同一会话同时只保留一份（新提案覆盖旧的，避免审到一半基线又被改）。
--
-- 幂等：MySQL 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 守卫后再执行
-- （与 048/049/083/089 同约定）。
-- =====================================================================

SET @col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_note_conversation'
      AND COLUMN_NAME = 'pending_proposal_json'
);
SET @sql := IF(@col = 0,
    'ALTER TABLE demo_note_conversation
        ADD COLUMN pending_proposal_json MEDIUMTEXT NULL
        COMMENT ''待用户接受/忽略的章节级改动提案（JSON）；NULL 表示无待审提案'' AFTER note_version',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
