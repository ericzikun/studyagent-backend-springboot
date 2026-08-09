-- Basic trial is a single $2.99 / 7-day product that always converts to Basic monthly.
-- The historical yearly row is retained for Stripe webhook/order/subscription lookup,
-- but is inactive so it cannot appear in the catalog or start a new Checkout.

USE studyagent;

START TRANSACTION;

UPDATE subscription_plans
SET tier = 'basic',
    billing_interval = 'month',
    offer_kind = 'basic_paid_trial',
    trial_days = 7,
    converts_to_plan_code = 'basic_monthly',
    price_cents = 299,
    currency = 'usd',
    is_active = 1,
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code = 'basic_trial_to_monthly';

UPDATE subscription_plans
SET is_active = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE plan_code = 'basic_trial_to_yearly';

COMMIT;

SELECT plan_code, billing_interval, offer_kind, trial_days,
       converts_to_plan_code, price_cents, currency, is_active,
       stripe_product_id, stripe_price_id
FROM subscription_plans
WHERE plan_code IN ('basic_trial_to_monthly', 'basic_trial_to_yearly')
ORDER BY plan_code;
