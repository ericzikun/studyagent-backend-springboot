# Billing Integrity Final Hardening Design

## Scope

This change closes the remaining commercialization audit gaps without changing
the normal subscription, plan-change, or add-on purchase paths:

1. stale invoice failure events can overwrite a recovered subscription;
2. a paid manual-upgrade quote can be fulfilled after its source state drifts;
3. subscription disputes do not freeze paid access;
4. a Stripe Checkout Session can survive a local order-write failure;
5. a paid add-on Session with no local order is retried until dead-letter;
6. Session ownership relies only on Stripe metadata;
7. `review_required` and `dead_letter` have no proactive billing alert.

## Subscription Event Authority

`invoice.payment_failed` and `invoice.payment_action_required` must retrieve the
current Stripe Subscription before changing local state. If the event is older
than the stored `(event_created, event_id)` watermark it is ignored. Otherwise,
the local status is derived from the authoritative remote Subscription status,
not from the event name.

If Stripe currently reports a non-payment-problem state such as `active`,
`trialing`, or `canceled`, the historical invoice failure must not mutate the
subscription, order, analytics, notification, or pending-upgrade state.

Equal-second events use the Stripe event ID as a deterministic tie-breaker.
This is not used as a substitute for Stripe authority; it only makes watermark
updates deterministic.

## Paid Manual Upgrade Revalidation

Before switching a paid manual-upgrade order, fulfillment revalidates:

- order owner and current local subscription owner;
- source Stripe Subscription ID;
- current plan code;
- original period start and end stored in `biz_context`;
- the remote subscription's current price and period;
- the source invoice ID and current net-paid amount when the quote used annual
  credit.

If any value drifted, the manual-upgrade PaymentIntent is refunded with an
idempotency key, the order becomes `refunded`, pending-upgrade state is cleared,
and the Stripe Subscription is not modified.

## Refund, Credit Note, and Dispute Policy

- A full refund of the current subscription invoice continues to cancel the
  Stripe Subscription immediately and remove access.
- A partial or historical refund and a credit note remain
  `review_required`. Those events are not sufficient by themselves to infer
  that the subscription contract must be canceled.
- A subscription dispute freezes local paid access immediately by setting the
  local subscription to `unpaid` and pausing add-ons.
- When dispute funds are reinstated or the dispute closes as won, the handler
  retrieves the authoritative Stripe Subscription, restores its state, and
  resumes eligible add-ons.

The recharge order records the active dispute. Subscription update events must
not restore paid access while the related order remains `disputed`.

## Checkout Compensation

After Stripe creates a Checkout Session, any local order persistence failure
triggers best-effort Session expiration before the original exception is
re-thrown. This applies to initial subscriptions, add-ons, and manual upgrades.

If a paid add-on webhook cannot find its local order, it performs an idempotent
full PaymentIntent refund and finishes successfully rather than retrying into
dead-letter.

## Ownership and Alerts

Session-status lookup compares Stripe metadata with the local
`recharge_orders.clerk_user_id` when an order exists. A mismatch fails closed.
Legacy Sessions without a local order continue to use authenticated-user versus
Stripe-metadata validation.

Transitions to `review_required` or `dead_letter` send a best-effort billing
operations notification. Notification failure must not roll back Stripe webhook
processing.

## Testing

Every behavior is introduced with a regression test that is observed failing
before production code changes. Verification includes focused billing tests,
the complete affected-module suites, and a full Maven reactor build.

## Repository Constraint

No Git commit is created unless the user explicitly requests it.
