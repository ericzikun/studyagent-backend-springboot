-- Stripe Sandbox Product/Price IDs for V2 billing.
-- This script is environment-specific and must never be used in Production.
-- Run after 056_subscription_catalog.sql.

USE studyagent;

START TRANSACTION;

UPDATE subscription_plans
SET stripe_product_id = 'prod_Ui3K6dy7ZVDVg8',
    stripe_price_id = 'price_1TidDv71hkxuoncTAHujO6vC'
WHERE plan_code = 'basic_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_Ui3K6dy7ZVDVg8',
    stripe_price_id = 'price_1TidG871hkxuoncTeQH81OsY'
WHERE plan_code = 'basic_yearly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_Ui3NJJK1SIdTPd',
    stripe_price_id = 'price_1TidGp71hkxuoncTagJdywVm'
WHERE plan_code = 'plus_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_Ui3NJJK1SIdTPd',
    stripe_price_id = 'price_1TidIS71hkxuoncTR18gFk1o'
WHERE plan_code = 'plus_yearly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_Ui3Q44UdEvgP4o',
    stripe_price_id = 'price_1TidJH71hkxuoncTooreTNpq'
WHERE plan_code = 'pro_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_Ui3Q44UdEvgP4o',
    stripe_price_id = 'price_1TidKB71hkxuoncTI8W3phC6'
WHERE plan_code = 'pro_yearly';

UPDATE addon_package_defs
SET stripe_product_id = 'prod_Ui3Uf4hleuWoQG',
    stripe_price_id = 'price_1TidNr71hkxuoncTid12LSXZ'
WHERE addon_code = 'addon_assignment_3';

UPDATE addon_package_defs
SET stripe_product_id = 'prod_Ui3VIgRW9nWGN7',
    stripe_price_id = 'price_1TidOK71hkxuoncTHZbZL2J7'
WHERE addon_code = 'addon_detection_5';

UPDATE addon_package_defs
SET stripe_product_id = 'prod_Ui3VbCp3yVIY3m',
    stripe_price_id = 'price_1TidOj71hkxuoncTdPpdKyKt'
WHERE addon_code = 'addon_humanizer_3';

COMMIT;

SELECT plan_code, price_cents, currency, stripe_product_id, stripe_price_id
FROM subscription_plans
ORDER BY display_order;

SELECT addon_code, price_cents, currency, stripe_product_id, stripe_price_id
FROM addon_package_defs
ORDER BY display_order;
