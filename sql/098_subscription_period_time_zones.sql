-- Explicit source zones for billing API presentation. Does not rewrite any historical dates.
-- Apply before deploying code that maps these columns. Safe to run repeatedly.
USE studyagent;
SET NAMES utf8mb4;

SET @period_zone_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='user_subscriptions' AND column_name='current_period_time_zone')=0,
    'ALTER TABLE user_subscriptions ADD COLUMN current_period_time_zone VARCHAR(64) NULL COMMENT ''Source zone for current period wall-clock dates, NULL legacy unknown''',
    'SELECT 1');
PREPARE period_zone_stmt FROM @period_zone_ddl;
EXECUTE period_zone_stmt;
DEALLOCATE PREPARE period_zone_stmt;

SET @period_zone_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='user_subscriptions' AND column_name='quota_period_time_zone')=0,
    'ALTER TABLE user_subscriptions ADD COLUMN quota_period_time_zone VARCHAR(64) NULL COMMENT ''Source zone for quota period wall-clock dates, NULL legacy unknown''',
    'SELECT 1');
PREPARE period_zone_stmt FROM @period_zone_ddl;
EXECUTE period_zone_stmt;
DEALLOCATE PREPARE period_zone_stmt;
