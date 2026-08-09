-- Hard-cut Free tier: zero free pool amounts so lapsed / unpaid users cannot consume.
-- Keep free_balance columns and free_refresh logic; amount 0 avoids rewriting the 4-pool consumer.

USE studyagent;

UPDATE ai_feature_defs
SET free_quota_amount = 0,
    updated_at = NOW()
WHERE feature_code IN ('task_create', 'ai_detection', 'humanizer');
