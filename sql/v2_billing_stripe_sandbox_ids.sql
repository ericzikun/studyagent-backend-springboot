-- Stripe Sandbox Product/Price IDs for V2 billing.
-- This script is environment-specific and must never be used in Production.
-- Run after 056_subscription_catalog.sql, 071_basic_trial_weekly.sql, and
-- 074_basic_trial_monthly_only.sql. Only the monthly-conversion Basic trial is
-- sellable; the yearly Price mapping remains solely for historical lookup.

USE studyagent;

START TRANSACTION;

UPDATE subscription_plans
SET stripe_product_id = 'prod_V2XQ7NFmV07Gc0',
    stripe_price_id = 'price_1U2SNV7GRT6LLkI1xPKAx9PO',
    converts_to_plan_code = 'basic_monthly',
    is_active = 1
WHERE plan_code = 'basic_trial_to_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_V2XQ7NFmV07Gc0',
    stripe_price_id = 'price_1U2VYo7GRT6LLkI1pYEEbcmw',
    is_active = 0
WHERE plan_code = 'basic_trial_to_yearly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_V2XQKO3FZiPKZI',
    stripe_price_id = 'price_1U2SNZ7GRT6LLkI1XgzarCSV'
WHERE plan_code = 'basic_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_V2XQKO3FZiPKZI',
    stripe_price_id = 'price_1U2SNd7GRT6LLkI1icheuGId'
WHERE plan_code = 'basic_yearly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_V2XQrWdt06ryxW',
    stripe_price_id = 'price_1U2SNh7GRT6LLkI1xwWeZJn2'
WHERE plan_code = 'plus_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_V2XQrWdt06ryxW',
    stripe_price_id = 'price_1U2SNl7GRT6LLkI1wICSgCbq'
WHERE plan_code = 'plus_yearly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_V2XRxk7tA14xQr',
    stripe_price_id = 'price_1U2SNp7GRT6LLkI17wGltgTq'
WHERE plan_code = 'pro_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_V2XRxk7tA14xQr',
    stripe_price_id = 'price_1U2SNt7GRT6LLkI1NwuJJvcc'
WHERE plan_code = 'pro_yearly';

UPDATE addon_package_defs
SET stripe_product_id = 'prod_V2XRyb7uDkhTVD',
    stripe_price_id = 'price_1U2SNx7GRT6LLkI10MleQRko'
WHERE addon_code = 'addon_assignment_3';

UPDATE addon_package_defs
SET stripe_product_id = 'prod_V2XRzr2BbiKqJA',
    stripe_price_id = 'price_1U2SO17GRT6LLkI17H9Bs8DQ'
WHERE addon_code = 'addon_detection_5';

UPDATE addon_package_defs
SET stripe_product_id = 'prod_V2XRbewO3qfwsD',
    stripe_price_id = 'price_1U2SO57GRT6LLkI1BAvC57zP'
WHERE addon_code = 'addon_humanizer_3';

COMMIT;

SELECT plan_code, offer_kind, billing_interval, converts_to_plan_code,
       price_cents, currency, is_active,
       stripe_product_id, stripe_price_id
FROM subscription_plans
ORDER BY display_order;

SELECT addon_code, price_cents, currency, stripe_product_id, stripe_price_id
FROM addon_package_defs
ORDER BY display_order;
