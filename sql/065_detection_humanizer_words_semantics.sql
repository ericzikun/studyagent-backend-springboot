USE studyagent;

-- Detection / Humanizer 已统一为字数语义。
-- 将 V2 quota 配置、套餐配置和加量包配置从“次数”映射到“字数”。
-- 兼容历史 run 口径：1 run = 10,000 words。

UPDATE ai_feature_defs
SET free_quota_period = 'monthly',
    updated_at = NOW()
WHERE feature_code = 'task_create';

UPDATE ai_feature_defs
SET quota_unit = 'words',
    free_quota_period = 'monthly',
    free_quota_amount = CASE feature_code
        WHEN 'ai_detection' THEN 3000
        WHEN 'humanizer' THEN 1000
        ELSE free_quota_amount
    END,
    updated_at = NOW()
WHERE feature_code IN ('ai_detection', 'humanizer');

UPDATE subscription_plans
SET detection_quota = CASE tier
        WHEN 'basic' THEN 10000
        WHEN 'plus' THEN 40000
        WHEN 'pro' THEN 100000
        ELSE detection_quota
    END,
    humanizer_quota = CASE tier
        WHEN 'basic' THEN 5000
        WHEN 'plus' THEN 20000
        WHEN 'pro' THEN 60000
        ELSE humanizer_quota
    END,
    updated_at = NOW()
WHERE is_active = 1;

UPDATE addon_package_defs
SET quota_amount = CASE addon_code
        WHEN 'addon_detection_5' THEN 20000
        WHEN 'addon_humanizer_3' THEN 10000
        ELSE quota_amount
    END,
    price_cents = CASE addon_code
        WHEN 'addon_detection_5' THEN 499
        WHEN 'addon_humanizer_3' THEN 699
        ELSE price_cents
    END,
    updated_at = NOW()
WHERE addon_code IN ('addon_detection_5', 'addon_humanizer_3');
