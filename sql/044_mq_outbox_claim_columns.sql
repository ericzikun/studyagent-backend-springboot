-- ========================================
-- 044: mq_outbox 多实例 claim 字段
-- 说明:
--   用于 Java command outbox 多实例安全投递。
--   SENDING + lease_until 过期的记录可被其他实例重新 claim。
-- ========================================

USE studyagent;

ALTER TABLE mq_outbox
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT '0=UNSENT, 1=SENT, 2=FAILED, 3=SENDING',
    ADD COLUMN worker_id       VARCHAR(128) DEFAULT NULL COMMENT '当前 claim / sending worker' AFTER error_message,
    ADD COLUMN lease_until     DATETIME     DEFAULT NULL COMMENT '当前 claim lease 截止时间' AFTER worker_id,
    ADD COLUMN last_claimed_at DATETIME     DEFAULT NULL COMMENT '最近一次 claim 时间' AFTER lease_until,
    ADD INDEX idx_mq_outbox_claim (status, next_retry_at, lease_until, id),
    ADD INDEX idx_mq_outbox_worker (worker_id, status, lease_until);
