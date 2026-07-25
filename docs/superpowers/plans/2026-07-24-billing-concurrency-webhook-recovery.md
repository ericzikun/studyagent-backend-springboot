# Billing Concurrency and Webhook Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure concurrent initial subscription requests reuse one Stripe Checkout Session and reclaim Stripe webhook events abandoned in `received` or `processing`.

**Architecture:** Commit a missing subscription row in a short `REQUIRES_NEW` bootstrap transaction, keep the existing subscription-row serialization, and make the pending initial-order lookup a MySQL locking current read. Extend the existing webhook retry worker with a configurable five-minute recovery lease and compare-and-set protection for stale processing claims.

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, MySQL, JUnit 5, Mockito, Maven, Stripe test sandbox.

**Repository constraint:** Do not create Git commits unless the user explicitly asks.

---

### Task 1: Make the initial pending-order lookup a locking read

**Files:**
- Modify: `agent-infra/src/test/java/com/studyagent/infra/service/billing/BillingDomainServiceImplTest.java`
- Modify: `agent-infra/src/main/java/com/studyagent/infra/service/billing/BillingDomainServiceImpl.java`
- Modify: `agent-infra/src/main/java/com/studyagent/infra/mapper/UserSubscriptionMapper.java`
- Create: `agent-infra/src/main/java/com/studyagent/infra/service/billing/UserSubscriptionBootstrapService.java`

- [x] **Step 1: Write the failing locking-read test**

Add a test that invokes `createSubscriptionCheckout`, captures the `Wrapper<RechargeOrderEntity>` passed to `rechargeOrderMapper.selectOne`, and asserts that the pending initial-order query ends with `LIMIT 1 FOR UPDATE`.

```java
@Test
void createSubscriptionCheckoutLocksPendingInitialOrderBeforeCreatingStripeSession() throws Exception {
    // Arrange a free user and an active plan with no pending order.
    // Invoke createSubscriptionCheckout.
    // Capture the RechargeOrder select wrapper and assert:
    assertTrue(wrapper.getCustomSqlSegment().contains("FOR UPDATE"));
}
```

- [x] **Step 2: Run the test and confirm RED**

Run:

```bash
mvn -pl agent-infra \
  -Dtest='BillingDomainServiceImplTest#createSubscriptionCheckoutLocksPendingInitialOrderBeforeCreatingStripeSession' \
  test
```

Expected: FAIL because the current query ends in `LIMIT 1` without `FOR UPDATE`.

- [x] **Step 3: Add the locking-read implementation**

Change only the pending initial-order query:

```java
.orderByDesc(RechargeOrderEntity::getCreatedAt)
.last("LIMIT 1 FOR UPDATE")
```

For brand-new users, bootstrap `user_subscriptions` with `INSERT IGNORE` in a separate `REQUIRES_NEW` transaction before the main transaction reads billing state. This makes the user row visible and committed before the main transaction locks it.

- [x] **Step 4: Run the test and confirm GREEN**

Run the same Maven test command. Expected: one passing test.

### Task 2: Reclaim stale received and processing webhook events

**Files:**
- Modify: `agent-infra/src/test/java/com/studyagent/infra/service/billing/StripeBillingWebhookServiceTest.java`
- Modify: `agent-infra/src/main/java/com/studyagent/infra/service/billing/StripeBillingWebhookService.java`

- [x] **Step 1: Write failing candidate-selection tests**

Add tests that capture the query wrapper passed to `webhookEventMapper.selectList` and verify the SQL condition contains all three candidate paths:

```text
failed + next_retry_at <= now
received + received_at <= staleBefore
processing + processing_started_at <= staleBefore
```

Also verify the configured timeout defaults to five minutes.

- [x] **Step 2: Write a failing stale-processing claim test**

Create a `processing` event with an old `processingStartedAt`, invoke the private `claim` method through reflection, capture the update wrapper, and assert its SQL condition includes the original `processing_started_at`.

```java
assertTrue(updateWrapper.getSqlSegment().contains("processing_started_at"));
```

- [x] **Step 3: Run the new tests and confirm RED**

Run:

```bash
mvn -pl agent-infra \
  -Dtest='StripeBillingWebhookServiceTest#retryWorkerSelectsFailedAndStaleReceivedOrProcessingEvents+staleProcessingClaimComparesOriginalProcessingTimestamp' \
  test
```

Expected: both tests fail because only `failed` is selected and the processing timestamp is not part of the claim compare-and-set.

- [x] **Step 4: Add the configurable recovery timeout**

Add:

```java
@Value("${billing.webhook-retry.processing-timeout-minutes:5}")
private long webhookProcessingTimeoutMinutes = 5L;
```

Use a helper that clamps non-positive configuration back to five minutes:

```java
private LocalDateTime webhookRecoveryCutoff(LocalDateTime now) {
    long timeoutMinutes = webhookProcessingTimeoutMinutes > 0
            ? webhookProcessingTimeoutMinutes
            : 5L;
    return now.minusMinutes(timeoutMinutes);
}
```

- [x] **Step 5: Extend retry candidate selection**

Build one grouped MyBatis query:

```java
LocalDateTime now = LocalDateTime.now();
LocalDateTime staleBefore = webhookRecoveryCutoff(now);

new LambdaQueryWrapper<StripeWebhookEventEntity>()
    .isNotNull(StripeWebhookEventEntity::getPayloadJson)
    .and(candidate -> candidate
        .and(failed -> failed
            .eq(StripeWebhookEventEntity::getStatus, "failed")
            .isNotNull(StripeWebhookEventEntity::getNextRetryAt)
            .le(StripeWebhookEventEntity::getNextRetryAt, now))
        .or(received -> received
            .eq(StripeWebhookEventEntity::getStatus, "received")
            .le(StripeWebhookEventEntity::getReceivedAt, staleBefore))
        .or(processing -> processing
            .eq(StripeWebhookEventEntity::getStatus, "processing")
            .le(StripeWebhookEventEntity::getProcessingStartedAt, staleBefore)))
    .orderByAsc(StripeWebhookEventEntity::getReceivedAt)
    .last("LIMIT " + batchSize);
```

- [x] **Step 6: Strengthen stale processing compare-and-set**

When the current state is `processing`, add:

```java
.eq(StripeWebhookEventEntity::getProcessingStartedAt,
        current.getProcessingStartedAt())
```

to the claim update. Continue incrementing `attempt_count` only when the compare-and-set succeeds.

- [x] **Step 7: Run the new tests and confirm GREEN**

Run the Task 2 test command. Expected: two passing tests.

### Task 3: Regression and local end-to-end verification

**Files:**
- No production files beyond Tasks 1 and 2.

- [x] **Step 1: Run focused billing tests**

```bash
mvn -pl agent-infra \
  -Dtest='BillingDomainServiceImplTest,StripeBillingWebhookServiceTest' \
  test
```

Expected: zero failures and zero errors.

- [x] **Step 2: Run the complete `agent-infra` test suite**

```bash
mvn -pl agent-infra test
```

Result: the full suite exposed unrelated pre-existing failures in
`QuotaDomainServiceDbTest` and `MockPyCommandConsumerTest`. Excluding only those
two untouched classes, 227 tests passed with 5 skipped.

- [x] **Step 3: Build all backend modules**

```bash
mvn install -DskipTests
```

Expected: all reactor modules report `SUCCESS`.

- [x] **Step 4: Re-run local concurrent Checkout verification**

Start the backend and Stripe webhook forwarder with the same safeguards used during reproduction. Before every Stripe command, assert:

```bash
[[ "$STRIPE_SECRET_KEY" == sk_test_* ]]
[[ "$(stripe accounts retrieve | jq -r '.settings.dashboard.display_name')" == 'New business 沙盒' ]]
[[ "$(stripe accounts retrieve | jq -r '.id')" == 'acct_1Sc4mIAswxmF1jsq' ]]
```

Use a new free local test user and issue eight concurrent `POST /v1/payment/subscription-checkout` requests for the same plan.

Expected:

- all successful responses return one distinct `cs_test` Session ID;
- exactly one pending `subscription_initial` order exists;
- Stripe reports one open Checkout Session for that customer.

- [x] **Step 5: Verify stale webhook recovery against local MySQL**

Insert one old `received` event and one old `processing` event with harmless stored payloads, wait for or invoke the retry method, and confirm each leaves its stale state. Remove synthetic test rows after verification.

- [x] **Step 6: Clean up sandbox state**

Expire the single open test Checkout Session. Confirm:

- no continuing test subscription;
- no open Checkout Session;
- no open invoice;
- no webhook event remains in `received`, `processing`, or `failed`;
- the backend and Stripe listener are stopped.

- [x] **Step 7: Review the final diff**

```bash
git diff --check
git diff --stat
git status --short
```

Expected: only the two service/test pairs plus the approved design and plan documents are changed; no generated artifacts or unrelated files appear.
