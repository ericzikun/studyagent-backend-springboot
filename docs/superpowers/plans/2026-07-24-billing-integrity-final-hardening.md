# Billing Integrity Final Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans
> to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Close the remaining billing state-ordering, paid-upgrade drift,
reversal, orphan-Checkout, ownership, and operational-alert gaps.

**Architecture:** Keep the existing centralized billing policy and webhook
service. Add authority and snapshot checks at the state-changing boundaries,
use idempotent Stripe compensation when a paid operation cannot be safely
fulfilled, and reuse the existing optional robot notification gateway for
operational alerts.

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, Stripe Java SDK, Gson,
JUnit 5, Mockito, Maven.

---

### Task 1: Authoritative invoice-failure synchronization

**Files:**
- Modify: `agent-infra/src/test/java/com/studyagent/infra/service/billing/StripeBillingWebhookServiceTest.java`
- Modify: `agent-infra/src/main/java/com/studyagent/infra/service/billing/StripeBillingWebhookService.java`

- [x] Write tests proving an older event is ignored, an authoritative `active`
  Subscription is not downgraded, and an authoritative `past_due`
  Subscription is synchronized.
- [x] Run the tests and confirm they fail for the missing authority and
  equal-second watermark behavior.
- [x] Pass event ID and creation time into the failure handler, retrieve the
  Stripe Subscription, derive local status from it, and atomically advance the
  `(created_at, event_id)` watermark.
- [x] Run the tests and confirm they pass.

### Task 2: Revalidate paid manual upgrades

**Files:**
- Modify: `agent-infra/src/test/java/com/studyagent/infra/service/billing/StripeBillingWebhookServiceTest.java`
- Modify: `agent-infra/src/main/java/com/studyagent/infra/service/billing/StripeBillingWebhookService.java`

- [x] Write dedicated tests for plan, invoice, corrupt-snapshot, and
  ineligible-status drift; assert no Stripe Subscription update occurs and the
  PaymentIntent is refunded. Source subscription, period, and net-paid drift
  use the same comparator/refund branch but do not have separate test methods.
- [x] Run the tests and confirm they fail because fulfillment currently
  switches without validating `biz_context`.
- [x] Parse the immutable quote snapshot, compare it to local and remote state,
  and add idempotent stale-quote refund handling.
- [x] Run the tests and confirm they pass.

### Task 3: Freeze and restore subscription disputes

**Files:**
- Modify: `agent-infra/src/test/java/com/studyagent/infra/service/billing/StripeBillingWebhookServiceTest.java`
- Modify: `agent-infra/src/main/java/com/studyagent/infra/service/billing/StripeBillingWebhookService.java`

- [x] Write failing tests for subscription dispute freeze, authoritative
  restore, and suppression of subscription updates while an order is disputed.
- [x] Implement row-locked freeze/restore and disputed-order guarding,
  including paid-invoice replay after a won dispute.
- [x] Run the tests and confirm they pass.

### Task 4: Compensate Checkout/order transaction gaps

**Files:**
- Modify: `agent-infra/src/test/java/com/studyagent/infra/service/billing/BillingDomainServiceImplTest.java`
- Modify: `agent-infra/src/test/java/com/studyagent/infra/service/billing/StripeBillingWebhookServiceTest.java`
- Modify: `agent-infra/src/main/java/com/studyagent/infra/service/billing/BillingDomainServiceImpl.java`
- Modify: `agent-infra/src/main/java/com/studyagent/infra/service/billing/StripeBillingWebhookService.java`

- [x] Write a dedicated initial-Checkout persistence-failure test and a paid
  add-on-without-order refund test. Add-on and manual-upgrade creation use the
  same Session-expiration helper but do not have separate failure test methods.
- [x] Add best-effort idempotent Session-expiration compensation and missing
  paid-order refunds.
- [x] Run the tests and confirm they pass.

### Task 5: Add local ownership defense and billing alerts

**Files:**
- Modify: `agent-infra/src/test/java/com/studyagent/infra/service/payment/PaymentDomainServiceImplTest.java`
- Modify: `agent-infra/src/main/java/com/studyagent/infra/service/payment/PaymentDomainServiceImpl.java`
- Modify: `agent-service/src/main/java/com/studyagent/service/domain/billing/BillingRobotNotifyGateway.java`
- Create: `agent-service/src/main/java/com/studyagent/service/domain/billing/BillingReviewNotifyRequest.java`
- Modify: `agent-api/src/main/java/com/studyagent/api/service/billing/BillingRobotNotifyGatewayImpl.java`
- Modify: `agent-api/src/main/java/com/studyagent/api/service/robot/RobotNotifyBillingService.java`
- Modify: `agent-infra/src/test/java/com/studyagent/infra/service/billing/StripeBillingWebhookServiceTest.java`

- [x] Write failing tests for Stripe/local owner mismatch and
  `review_required` notification. Dead-letter uses the same best-effort alert
  method but does not have a separate notification test.
- [x] Add the local order ownership check and best-effort alert gateway method.
- [x] Run affected service, infra, and API tests.

### Task 6: Regression verification

- [x] Run focused billing and payment tests: 134 passed.
- [x] Run the broad common/service/infra reactor suite while excluding the two
  documented unrelated pre-existing failures
  (`QuotaDomainServiceDbTest`, `MockPyCommandConsumerTest`).
- [x] Run the API module suite: 86 passed.
- [x] Run the full Maven reactor build with tests skipped only after test suites
  have passed.
- [x] Run `git diff --check`, inspect the complete diff, and confirm no unrelated
  files or credentials were added.

**Repository constraint:** Do not commit.
