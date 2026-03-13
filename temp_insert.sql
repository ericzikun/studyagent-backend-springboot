INSERT INTO ai_feature_packages (feature_code, package_code, package_name, quota_amount, price_cents, currency, is_active, display_order, created_at, updated_at)
VALUES
    ('ai_detection', 'detection_10k',  '10,000 Words',  10000,  199, 'usd', 1, 20, NOW(), NOW()),
    ('ai_detection', 'detection_50k',  '50,000 Words',  50000,  799, 'usd', 1, 21, NOW(), NOW()),
    ('ai_detection', 'detection_200k', '200,000 Words', 200000, 2399, 'usd', 1, 22, NOW(), NOW())
ON DUPLICATE KEY UPDATE updated_at = NOW();

INSERT INTO ai_feature_packages (feature_code, package_code, package_name, quota_amount, price_cents, currency, is_active, display_order, created_at, updated_at)
VALUES
    ('humanizer', 'humanizer_10k',  '10,000 Words',  10000,  299, 'usd', 1, 30, NOW(), NOW()),
    ('humanizer', 'humanizer_50k',  '50,000 Words',  50000, 1199, 'usd', 1, 31, NOW(), NOW()),
    ('humanizer', 'humanizer_200k', '200,000 Words', 200000, 3999, 'usd', 1, 32, NOW(), NOW())
ON DUPLICATE KEY UPDATE updated_at = NOW();
