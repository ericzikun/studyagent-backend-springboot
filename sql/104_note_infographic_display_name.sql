-- Rename the metered infographic feature shown in account usage and quota screens.
-- One-shot migration. Apply with --default-character-set=utf8mb4.

USE studyagent;

UPDATE ai_feature_defs
SET feature_name = 'Infographics',
    updated_at = CURRENT_TIMESTAMP
WHERE feature_code = 'note_infographic';
