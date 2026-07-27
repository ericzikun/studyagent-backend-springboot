# Billing Checkout Concurrency and Webhook Recovery Design

## Scope

This change fixes two confirmed billing defects:

1. Concurrent initial subscription Checkout requests can create multiple Stripe Checkout Sessions.
2. Stripe webhook events left in `received` or `processing` are never reclaimed by the internal retry scheduler.

It does not change webhook ordering policy, `review_required` handling, refund correlation, or billing state transitions.

## Initial Checkout Concurrency

`createSubscriptionCheckout` already locks the user's `user_subscriptions` row. However, two gaps exist:

- for a brand-new user, concurrent requests can all observe that the subscription row is absent and then compete to insert it;
- the later pending-order lookup is a non-locking consistent read and can miss an order committed by a preceding request under MySQL `REPEATABLE READ`.

Before the main Checkout transaction reads billing state, `UserSubscriptionBootstrapService` performs an `INSERT IGNORE` in a short `REQUIRES_NEW` transaction. The row is committed before the main transaction establishes its read snapshot. The main transaction can then safely serialize requests with the existing subscription-row `FOR UPDATE`.

The pending initial-order lookup also becomes a locking current read using `FOR UPDATE`. A concurrent request therefore waits for the first request to commit, sees the newly created pending order, retrieves its Stripe Session, and reuses it.

No advisory lock, new lock table, or schema migration is required. The separate bootstrap transaction is necessary: real eight-request tests showed that catching a duplicate-key exception, or combining an idempotent insert and locking read in the same transaction, can deadlock during InnoDB lock conversion.

## Webhook Recovery

The retry scheduler will consider three candidate groups:

- `failed` events whose non-null `next_retry_at` is due;
- `received` events whose `received_at` is at least five minutes old;
- `processing` events whose `processing_started_at` is at least five minutes old.

The timeout is configurable as `billing.webhook-retry.processing-timeout-minutes` and defaults to five minutes.

Existing terminal states remain excluded:

- `succeeded`
- `ignored`
- `review_required`
- `dead_letter`

Existing exponential backoff and maximum-attempt behavior remain unchanged.

## Multi-instance Claim Safety

For a stale `processing` event, the compare-and-set update will match both:

- the current status; and
- the previously read `processing_started_at`.

Only one scheduler instance can replace that timestamp and increment `attempt_count`. Other instances fail the claim and do not count the event as retried.

Claims from `received` and `failed` remain safe because the status changes to `processing`.

## Testing

Tests will be written before production changes and observed failing for the intended reason.

Coverage will include:

- the subscription row is bootstrapped before the Checkout flow;
- pending initial-order lookup is a locking current read;
- stale `received` events are eligible for recovery;
- stale `processing` events are eligible for recovery;
- recent `processing` events are not eligible;
- stale `processing` compare-and-set includes the original timestamp;
- an event that loses the claim is not counted as retried;
- existing failed-event backoff and terminal-state behavior remain unchanged.

After unit tests pass, verification will include the full `agent-infra` test suite, a Maven build, and a local concurrent Checkout request run against the Stripe `New business 沙盒` account with `livemode=false`.

## Success Criteria

- Concurrent initial Checkout requests for the same free user and plan return one Stripe Session.
- A brand-new user does not produce duplicate-key errors or InnoDB deadlocks under concurrent requests.
- Only one pending initial subscription order is created.
- `received` and `processing` events older than five minutes can be reclaimed.
- Fresh `processing` events are not reclaimed.
- Multi-instance claim attempts do not process the same stale event concurrently.
- No database migration is introduced.
