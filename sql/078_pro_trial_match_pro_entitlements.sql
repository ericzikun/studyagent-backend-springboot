-- Align Pro Trial functional entitlements with full Pro:
--   max_files / max_followup_edits = unlimited (NULL)
--   allowed_output_types = writing + ppt + coding
-- Quotas (assignment / detection / humanizer) stay at trial-week levels.
-- Idempotent — safe to re-run on Sandbox and Production.

USE studyagent;

UPDATE subscription_plans
SET max_files = NULL,
    max_followup_edits = NULL,
    allowed_output_types = JSON_ARRAY('writing', 'ppt', 'coding'),
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code IN ('pro_trial_to_monthly', 'pro_trial_to_yearly');

SELECT plan_code, assignment_quota, detection_quota, humanizer_quota,
       max_files, max_followup_edits, allowed_output_types, is_active
FROM subscription_plans
WHERE plan_code IN ('pro_trial_to_monthly', 'pro_trial_to_yearly', 'pro_monthly')
ORDER BY plan_code;
