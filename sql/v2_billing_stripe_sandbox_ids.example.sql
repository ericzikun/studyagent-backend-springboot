-- Copy this file to an environment-only deployment script and replace every placeholder.
-- Do not commit real Sandbox/Live identifiers or secret keys.

USE studyagent;

-- Historical Basic Trial Prices (not sellable). Keep for lookup.
UPDATE subscription_plans
SET stripe_product_id = 'prod_BASIC_TRIAL',
    stripe_price_id = 'price_BASIC_TRIAL_TO_MONTHLY',
    converts_to_plan_code = 'basic_monthly',
    is_active = 0
WHERE plan_code = 'basic_trial_to_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_BASIC_TRIAL',
    stripe_price_id = 'price_BASIC_TRIAL_TO_YEARLY',
    is_active = 0
WHERE plan_code = 'basic_trial_to_yearly';

-- Sellable Pro Trial: weekly recurring $2.99 Prices (NOT one_time).
UPDATE subscription_plans
SET stripe_product_id = 'prod_PRO_TRIAL',
    stripe_price_id = 'price_PRO_TRIAL_TO_MONTHLY',
    converts_to_plan_code = 'pro_monthly',
    is_active = 1
WHERE plan_code = 'pro_trial_to_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_PRO_TRIAL',
    stripe_price_id = 'price_PRO_TRIAL_TO_YEARLY',
    converts_to_plan_code = 'pro_yearly',
    is_active = 1
WHERE plan_code = 'pro_trial_to_yearly';

UPDATE subscription_plans
SET is_active = 0
WHERE plan_code = 'pro_trial_once';

UPDATE subscription_plans
SET stripe_product_id = 'prod_BASIC', stripe_price_id = 'price_BASIC_MONTHLY'
WHERE plan_code = 'basic_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_BASIC', stripe_price_id = 'price_BASIC_YEARLY'
WHERE plan_code = 'basic_yearly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_PLUS', stripe_price_id = 'price_PLUS_MONTHLY'
WHERE plan_code = 'plus_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_PLUS', stripe_price_id = 'price_PLUS_YEARLY'
WHERE plan_code = 'plus_yearly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_PRO', stripe_price_id = 'price_PRO_MONTHLY'
WHERE plan_code = 'pro_monthly';

UPDATE subscription_plans
SET stripe_product_id = 'prod_PRO', stripe_price_id = 'price_PRO_YEARLY'
WHERE plan_code = 'pro_yearly';

-- Reuse existing one-time Prices when they exactly match the V2 add-on amount and currency.
UPDATE addon_package_defs
SET stripe_product_id = 'prod_ADDON_ASSIGNMENT', stripe_price_id = 'price_ADDON_ASSIGNMENT_3'
WHERE addon_code = 'addon_assignment_3';

UPDATE addon_package_defs
SET stripe_product_id = 'prod_ADDON_DETECTION', stripe_price_id = 'price_ADDON_DETECTION_5'
WHERE addon_code = 'addon_detection_5';

UPDATE addon_package_defs
SET stripe_product_id = 'prod_ADDON_HUMANIZER', stripe_price_id = 'price_ADDON_HUMANIZER_3'
WHERE addon_code = 'addon_humanizer_3';

SELECT plan_code, converts_to_plan_code, is_active,
       stripe_product_id, stripe_price_id
FROM subscription_plans
ORDER BY display_order;

SELECT addon_code, stripe_product_id, stripe_price_id
FROM addon_package_defs
ORDER BY display_order;
