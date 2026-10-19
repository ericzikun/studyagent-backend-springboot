-- ---------------------------------------------------------------------------
-- 094: rollout 台账的价列改为可空
--
-- dry-run 会把 skipped / conflict 合同也落库（如 price_unresolved、
-- subscription_shape_unreadable、stripe_error），这些行没有 source/target
-- price 或币种；DDL 却声明为 NOT NULL，导致首条此类记录插入即失败：
--   Field 'source_price_id' doesn't have a default value
-- 这里改成可空。MODIFY 可重复执行。
-- ---------------------------------------------------------------------------
SET NAMES utf8mb4;

ALTER TABLE subscription_price_rollouts
    MODIFY COLUMN currency CHAR(3) NULL,
    MODIFY COLUMN source_price_id VARCHAR(128) NULL,
    MODIFY COLUMN source_version VARCHAR(32) NULL,
    MODIFY COLUMN target_price_id VARCHAR(128) NULL;
