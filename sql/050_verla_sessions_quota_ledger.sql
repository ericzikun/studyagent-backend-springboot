-- ========================================
-- 迁移脚本 050: verla_sessions 接入商业化额度
-- 创建日期: 2026-05-30
-- 说明: 为 V2 verla 链路扣费/退款绑定 quota_ledger 流水
--   - quota_ledger_id：本 agent session 的扣费流水 ID，refund 时按此反查
--   - quota_amount   ：扣费数量（次 / 字）；仅用于审计与排错
-- 参考: docs/V2/V2-商业化额度接入技术方案.md §4
-- ========================================

ALTER TABLE verla_sessions
    ADD COLUMN quota_ledger_id BIGINT NULL COMMENT '本 session 扣费流水 ID（refund 索引）' AFTER feature_code,
    ADD COLUMN quota_amount    BIGINT NULL COMMENT '本 session 扣费数量（次或字，仅记录便于排错）' AFTER quota_ledger_id,
    ADD INDEX idx_quota_ledger_id (quota_ledger_id);

SELECT '✅ Migration 050: verla_sessions add quota_ledger_id' AS result;
