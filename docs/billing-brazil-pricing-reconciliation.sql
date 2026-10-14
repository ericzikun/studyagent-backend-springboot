-- Read-only checks; no repair UPDATE, DDL, Stripe writes or production execution in this task.
-- Missing conversion schedule after a successful introductory purchase.
SELECT id, clerk_user_id, plan_code, status, stripe_subscription_id,
       stripe_schedule_id, pending_plan_code, current_period_end
FROM user_subscriptions
WHERE subscription_phase = 'intro'
  AND status IN ('active', 'trialing')
  AND stripe_subscription_id IS NOT NULL
  AND (stripe_schedule_id IS NULL OR stripe_schedule_id = ''
       OR pending_plan_code IS NULL OR pending_plan_code = '');

-- Existing webhook retry/dead-letter queue: use the existing reconciliation path.
SELECT event_id, event_type, object_id, status, attempt_count,
       next_retry_at, dead_lettered_at
FROM stripe_webhook_events
WHERE status <> 'processed'
ORDER BY received_at DESC
LIMIT 100;

-- Confirmed vs actual payment facts are kept in existing order fields.
SELECT order_no, clerk_user_id, order_type, plan_code, currency,
       price_cents, quoted_amount_cents, stripe_session_id,
       stripe_subscription_id, stripe_invoice_id, status
FROM recharge_orders
WHERE order_type = 'subscription_intro_trial'
ORDER BY created_at DESC
LIMIT 100;
