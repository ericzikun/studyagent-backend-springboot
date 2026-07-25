package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyagent.common.analytics.AnalyticsEvents;
import com.studyagent.common.analytics.AnalyticsService;
import com.stripe.Stripe;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.checkout.Session;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionSchedule;
import com.stripe.param.SubscriptionUpdateParams;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.entity.StripeWebhookEventEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.StripeWebhookEventMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper;
import com.studyagent.service.domain.billing.BillingQuotaGateway;
import com.studyagent.service.domain.billing.BillingReviewNotifyRequest;
import com.studyagent.service.domain.billing.BillingRobotNotifyGateway;
import com.studyagent.service.domain.quota.AddonGrantSnapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StripeBillingWebhookServiceTest {
    @BeforeAll
    static void initTableInfo() {
        MybatisPlusTableInfoTestHelper.initTableInfo(RechargeOrderEntity.class);
        MybatisPlusTableInfoTestHelper.initTableInfo(UserSubscriptionEntity.class);
        MybatisPlusTableInfoTestHelper.initTableInfo(StripeWebhookEventEntity.class);
    }

    @Mock
    private StripeWebhookEventMapper webhookEventMapper;
    @Mock
    private UserSubscriptionMapper userSubscriptionMapper;
    @Mock
    private SubscriptionPlanMapper subscriptionPlanMapper;
    @Mock
    private AddonPackageDefMapper addonPackageDefMapper;
    @Mock
    private RechargeOrderMapper rechargeOrderMapper;
    @Mock
    private AnalyticsService analyticsService;
    @Mock
    private BillingQuotaGateway billingQuotaGateway;
    @Mock
    private ObjectProvider<BillingQuotaGateway> quotaGatewayProvider;
    @Mock
    private ObjectProvider<BillingRobotNotifyGateway> billingRobotNotifyGatewayProvider;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    @Test
    void supportsSubscriptionLifecycleEvents() {
        Event event = event("invoice.paid", "invoice", null);
        assertTrue(service().supports(event));
    }

    @Test
    void receiveStoresRawPayloadForInternalRetry() throws Exception {
        Event event = event("customer.subscription.updated", "subscription", null);

        var method = StripeBillingWebhookService.class.getDeclaredMethod("receive", Event.class);
        method.setAccessible(true);
        method.invoke(service(), event);

        ArgumentCaptor<StripeWebhookEventEntity> captor =
                ArgumentCaptor.forClass(StripeWebhookEventEntity.class);
        verify(webhookEventMapper).insert(captor.capture());
        assertTrue(captor.getValue().getPayloadJson().contains("customer.subscription.updated"));
        assertNull(captor.getValue().getNextRetryAt());
    }

    @Test
    void webhookRetryBackoffIsBounded() {
        LocalDateTime failedAt = LocalDateTime.parse("2026-07-15T10:00:00");

        assertEquals(failedAt.plusMinutes(1),
                StripeBillingWebhookService.calculateNextRetryAt(failedAt, 1));
        assertEquals(failedAt.plusMinutes(60),
                StripeBillingWebhookService.calculateNextRetryAt(failedAt, 20));
    }

    @Test
    void unmappedRefundEventRequiresReviewWithoutRetryFailure() {
        Event event = reversalEvent(
                "evt_unmapped_refund",
                "charge.refunded",
                "charge",
                "\"payment_intent\":\"pi_external\",\"amount\":9900,"
                        + "\"amount_refunded\":9900,\"refunded\":true");
        when(billingRobotNotifyGatewayProvider.getIfAvailable())
                .thenReturn(org.mockito.Mockito.mock(BillingRobotNotifyGateway.class));

        assertReviewRequired(event);

        verify(billingRobotNotifyGatewayProvider.getIfAvailable())
                .notifyBillingReviewRequired(any());
    }

    @Test
    void unmappedDisputeEventRequiresReviewWithoutRetryFailure() {
        Event event = reversalEvent(
                "evt_unmapped_dispute",
                "charge.dispute.created",
                "dispute",
                "\"payment_intent\":\"pi_external\",\"charge\":\"ch_external\","
                        + "\"amount\":9900,\"status\":\"needs_response\"");

        assertReviewRequired(event);
    }

    @Test
    void unmappedCreditNoteEventRequiresReviewWithoutRetryFailure() {
        Event event = reversalEvent(
                "evt_unmapped_credit_note",
                "credit_note.created",
                "credit_note",
                "\"invoice\":\"in_external\",\"status\":\"issued\"");

        assertReviewRequired(event);
    }

    @Test
    void matchedCreditNoteMarksOrderForReviewAndSendsAlert() throws Exception {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(41L);
        order.setOrderType("subscription_renewal");
        order.setStripeInvoiceId("in_credit_note");
        when(rechargeOrderMapper.selectOne(any())).thenReturn(order);
        BillingRobotNotifyGateway notifyGateway =
                org.mockito.Mockito.mock(BillingRobotNotifyGateway.class);
        when(billingRobotNotifyGatewayProvider.getIfAvailable()).thenReturn(notifyGateway);
        Event event = reversalEvent(
                "evt_matched_credit_note",
                "credit_note.created",
                "credit_note",
                "\"invoice\":\"in_credit_note\",\"status\":\"issued\"");

        invokeHandle(service(), event);

        verify(rechargeOrderMapper).update(isNull(), any());
        ArgumentCaptor<BillingReviewNotifyRequest> notification =
                ArgumentCaptor.forClass(BillingReviewNotifyRequest.class);
        verify(notifyGateway).notifyBillingReviewRequired(notification.capture());
        assertEquals("evt_matched_credit_note", notification.getValue().getStripeEventId());
        assertEquals("review_required", notification.getValue().getStatus());
        assertEquals("obj_external", notification.getValue().getObjectId());
    }

    @Test
    void retryWorkerDoesNotCountEventThatWasNotClaimed() {
        Event event = event("invoice.paid", "invoice", null);
        StripeWebhookEventEntity due = webhookEvent(event, "failed");
        StripeWebhookEventEntity alreadySucceeded = webhookEvent(event, "succeeded");
        when(webhookEventMapper.selectList(any())).thenReturn(List.of(due));
        when(webhookEventMapper.selectById(event.getId())).thenReturn(alreadySucceeded);

        assertEquals(0, service().retryDueEvents(10));
    }

    @Test
    void retryWorkerSelectsFailedAndStaleReceivedOrProcessingEvents() {
        when(webhookEventMapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, service().retryDueEvents(10));

        ArgumentCaptor<Wrapper> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(webhookEventMapper).selectList(queryCaptor.capture());
        LambdaQueryWrapper<?> query = (LambdaQueryWrapper<?>) queryCaptor.getValue();
        String sql = query.getCustomSqlSegment();
        List<Object> values = new ArrayList<>(query.getParamNameValuePairs().values());
        assertTrue(values.contains("failed"));
        assertTrue(values.contains("received"));
        assertTrue(values.contains("processing"));
        assertTrue(sql.contains("next_retry_at"));
        assertTrue(sql.contains("received_at"));
        assertTrue(sql.contains("processing_started_at"));
    }

    @Test
    void staleProcessingClaimComparesOriginalProcessingTimestamp() throws Exception {
        Event event = event("invoice.paid", "invoice", null);
        StripeWebhookEventEntity stale = webhookEvent(event, "processing");
        stale.setProcessingStartedAt(LocalDateTime.now().minusMinutes(10));
        when(webhookEventMapper.selectById(event.getId())).thenReturn(stale);
        when(webhookEventMapper.update(isNull(), any())).thenReturn(1);

        assertTrue(invokeClaim(service(), event.getId()));

        ArgumentCaptor<LambdaUpdateWrapper<StripeWebhookEventEntity>> updateCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(webhookEventMapper).update(isNull(), updateCaptor.capture());
        assertTrue(updateCaptor.getValue().getSqlSegment().contains("processing_started_at"));
    }

    @Test
    void supportsInvoicePaymentSucceededEvents() {
        Event event = event("invoice.payment_succeeded", "invoice", null);
        assertTrue(service().supports(event));
    }

    @Test
    void supportsInvoicePaymentPaidEvents() {
        Event event = event("invoice_payment.paid", "invoice_payment", null);
        assertTrue(service().supports(event));
    }

    @Test
    void chargeRefundedAdjustsAddonGrantFromCumulativeRefund() throws Exception {
        RechargeOrderEntity order = addonOrder("cs_refunded");
        order.setStripePaymentIntentId("pi_refunded");
        when(rechargeOrderMapper.selectOne(any())).thenReturn(order);
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);
        String json = """
                {
                  "id":"evt_charge_refunded",
                  "object":"event",
                  "created":1781696510,
                  "api_version":"2023-10-16",
                  "type":"charge.refunded",
                  "data":{"object":{
                    "id":"ch_refunded",
                    "object":"charge",
                    "payment_intent":"pi_refunded",
                    "amount":9900,
                    "amount_refunded":9900,
                    "refunded":true
                  }}
                }
                """;
        Event event = com.stripe.net.ApiResource.GSON.fromJson(json, Event.class);

        assertTrue(service().supports(event));
        invokeHandle(service(), event);

        verify(billingQuotaGateway).adjustAddonForRefund(
                "pi_refunded", "charge:ch_refunded:9900", 9900L, 9900L);
        verify(rechargeOrderMapper).update(isNull(), any());
    }

    @Test
    void disputeCreatedFreezesAddonGrant() throws Exception {
        RechargeOrderEntity order = addonOrder("cs_disputed");
        order.setStripePaymentIntentId("pi_disputed");
        when(rechargeOrderMapper.selectOne(any())).thenReturn(order);
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);
        String json = """
                {
                  "id":"evt_dispute_created",
                  "object":"event",
                  "created":1781696510,
                  "api_version":"2023-10-16",
                  "type":"charge.dispute.created",
                  "data":{"object":{
                    "id":"dp_1",
                    "object":"dispute",
                    "payment_intent":"pi_disputed",
                    "charge":"ch_disputed",
                    "amount":9900,
                    "status":"needs_response"
                  }}
                }
                """;
        Event event = com.stripe.net.ApiResource.GSON.fromJson(json, Event.class);

        assertTrue(service().supports(event));
        invokeHandle(service(), event);

        verify(billingQuotaGateway).freezeAddonForDispute("pi_disputed", "dp_1");
        verify(rechargeOrderMapper).update(isNull(), any());
    }

    @Test
    void subscriptionDisputeFreezesPaidAccessAndAddons() throws Exception {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(78L);
        order.setOrderType("subscription_renewal");
        order.setClerkUserId("user_1");
        order.setStripePaymentIntentId("pi_subscription_dispute");
        order.setStripeSubscriptionId("sub_disputed");
        when(rechargeOrderMapper.selectOne(any())).thenReturn(order);

        UserSubscriptionEntity current = activeSubscription();
        current.setId(89L);
        current.setStripeSubscriptionId("sub_disputed");
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(current);
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);

        List<Object> subscriptionUpdateValues = new ArrayList<>();
        when(userSubscriptionMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<?> wrapper = invocation.getArgument(1);
            subscriptionUpdateValues.addAll(wrapper.getParamNameValuePairs().values());
            return 1;
        });
        Event event = reversalEvent(
                "evt_subscription_dispute",
                "charge.dispute.created",
                "dispute",
                "\"payment_intent\":\"pi_subscription_dispute\","
                        + "\"charge\":\"ch_subscription_dispute\","
                        + "\"amount\":1999,\"status\":\"needs_response\"");

        invokeHandle(service(), event);

        assertTrue(subscriptionUpdateValues.contains("unpaid"));
        verify(billingQuotaGateway).pauseAddons(
                "user_1",
                "sub_disputed",
                "dispute:obj_external:pause-addons");
        verify(userSubscriptionMapper).selectByUserForUpdate("user_1");
    }

    @Test
    void subscriptionDisputeWonRestoresAuthoritativeAccessAndAddons() throws Exception {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(79L);
        order.setOrderType("subscription_renewal");
        order.setStatus("disputed");
        order.setClerkUserId("user_1");
        order.setStripePaymentIntentId("pi_subscription_dispute_won");
        order.setStripeSubscriptionId("sub_disputed");
        when(rechargeOrderMapper.selectOne(any())).thenReturn(order);

        UserSubscriptionEntity current = activeSubscription();
        current.setId(90L);
        current.setStatus("unpaid");
        current.setStripeSubscriptionId("sub_disputed");
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(current);
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);

        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode("plus_monthly");
        plan.setTier("plus");
        plan.setStripePriceId("price_plus");
        plan.setBillingInterval("month");
        when(subscriptionPlanMapper.selectOne(any())).thenReturn(plan);

        Subscription authoritative = new Subscription();
        authoritative.setId("sub_disputed");
        authoritative.setCustomer("cus_disputed");
        authoritative.setStatus("active");
        authoritative.setMetadata(Map.of("clerk_user_id", "user_1"));
        authoritative.setLatestInvoice("in_paid_during_dispute");
        authoritative.setCurrentPeriodStart(1782864000L);
        authoritative.setCurrentPeriodEnd(1785542400L);
        SubscriptionItem item = new SubscriptionItem();
        Price price = new Price();
        price.setId("price_plus");
        item.setPrice(price);
        authoritative.setItems(new com.stripe.model.SubscriptionItemCollection());
        authoritative.getItems().setData(List.of(item));

        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                return authoritative;
            }

            @Override
            Invoice retrieveStripeInvoice(String invoiceId) {
                Invoice invoice = new Invoice();
                invoice.setId(invoiceId);
                invoice.setStatus("paid");
                invoice.setPaid(true);
                invoice.setSubscription("sub_disputed");
                return invoice;
            }
        };
        Event event = reversalEvent(
                "evt_subscription_dispute_won",
                "charge.dispute.closed",
                "dispute",
                "\"payment_intent\":\"pi_subscription_dispute_won\","
                        + "\"charge\":\"ch_subscription_dispute_won\","
                        + "\"amount\":1999,\"status\":\"won\"");

        invokeHandle(service, event);

        verify(userSubscriptionMapper, org.mockito.Mockito.atLeastOnce())
                .selectByUserForUpdate("user_1");
        verify(userSubscriptionMapper).update(isNull(), any());
        verify(billingQuotaGateway).resetFromPaidInvoice(
                eq("user_1"),
                eq("sub_disputed"),
                eq("plus_monthly"),
                any(),
                any(),
                eq("in_paid_during_dispute"),
                any());
        verify(billingQuotaGateway).resumeEligibleAddons(
                "user_1",
                "sub_disputed",
                "subscription:sub_disputed:resume-addons:in_paid_during_dispute");
    }

    @Test
    void fullRefundOfCurrentSubscriptionInvoiceCancelsCurrentSubscription() throws Exception {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(77L);
        order.setOrderType("subscription_renewal");
        order.setClerkUserId("user_1");
        order.setPlanCode("plus_monthly");
        order.setStripePaymentIntentId("pi_subscription_refund");
        order.setStripeSubscriptionId("sub_current");
        order.setStripeInvoiceId("in_current");
        when(rechargeOrderMapper.selectOne(any())).thenReturn(order);
        UserSubscriptionEntity current = activeSubscription();
        current.setId(88L);
        current.setStripeSubscriptionId("sub_current");
        current.setPlanCode("plus_monthly");
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(current);
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);
        AtomicBoolean canceledInStripe = new AtomicBoolean(false);
        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                Subscription subscription = new Subscription();
                subscription.setId(subscriptionId);
                subscription.setLatestInvoice("in_current");
                return subscription;
            }

            Subscription cancelStripeSubscription(Subscription subscription) {
                canceledInStripe.set(true);
                subscription.setStatus("canceled");
                return subscription;
            }
        };
        String json = """
                {"id":"evt_subscription_refund","object":"event","created":1781696510,
                 "api_version":"2023-10-16","type":"charge.refunded","data":{"object":{
                   "id":"ch_subscription_refund","object":"charge",
                   "payment_intent":"pi_subscription_refund","amount":1999,
                   "amount_refunded":1999,"refunded":true}}}
                """;

        invokeHandle(service, com.stripe.net.ApiResource.GSON.fromJson(json, Event.class));

        verify(billingQuotaGateway).clearPlanQuota(
                "user_1", "sub_current", "plus_monthly", "refund:ch_subscription_refund:plan");
        verify(billingQuotaGateway).pauseAddons(
                "user_1", "sub_current", "refund:ch_subscription_refund:addons");
        verify(userSubscriptionMapper).selectByUserForUpdate("user_1");
        verify(userSubscriptionMapper).update(isNull(), any());
        assertTrue(canceledInStripe.get());
    }

    @Test
    void fullRefundOfCurrentManualUpgradeCancelsCurrentSubscriptionWithoutInvoice() throws Exception {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(78L);
        order.setOrderType("subscription_upgrade_manual");
        order.setClerkUserId("user_1");
        order.setPlanCode("pro_monthly");
        order.setStripePaymentIntentId("pi_manual_upgrade_refund");
        order.setStripeSubscriptionId("sub_current");
        when(rechargeOrderMapper.selectOne(any())).thenReturn(order);
        UserSubscriptionEntity current = activeSubscription();
        current.setId(89L);
        current.setStripeSubscriptionId("sub_current");
        current.setPlanCode("pro_monthly");
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(current);
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);
        AtomicBoolean canceledInStripe = new AtomicBoolean(false);
        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                Subscription subscription = new Subscription();
                subscription.setId(subscriptionId);
                subscription.setLatestInvoice("in_unrelated");
                return subscription;
            }

            @Override
            Subscription cancelStripeSubscription(Subscription subscription) {
                canceledInStripe.set(true);
                subscription.setStatus("canceled");
                return subscription;
            }
        };
        String json = """
                {"id":"evt_manual_upgrade_refund","object":"event","created":1781696510,
                 "api_version":"2023-10-16","type":"charge.refunded","data":{"object":{
                   "id":"ch_manual_upgrade_refund","object":"charge",
                   "payment_intent":"pi_manual_upgrade_refund","amount":9588,
                   "amount_refunded":9588,"refunded":true}}}
                """;

        invokeHandle(service, com.stripe.net.ApiResource.GSON.fromJson(json, Event.class));

        verify(billingQuotaGateway).clearPlanQuota(
                "user_1", "sub_current", "pro_monthly", "refund:ch_manual_upgrade_refund:plan");
        verify(billingQuotaGateway).pauseAddons(
                "user_1", "sub_current", "refund:ch_manual_upgrade_refund:addons");
        verify(userSubscriptionMapper).update(isNull(), any());
        assertTrue(canceledInStripe.get());
    }

    @Test
    void supportsSubscriptionScheduleEvents() {
        Event event = event("subscription_schedule.updated", "subscription_schedule", null);
        assertTrue(service().supports(event));
    }

    @Test
    void invoicePaidRetriesReuseExistingInvoiceOrderBeforePendingOrder() {
        RechargeOrderEntity existingInvoiceOrder = new RechargeOrderEntity();
        existingInvoiceOrder.setId(140L);
        existingInvoiceOrder.setOrderNo("RO_existing");
        existingInvoiceOrder.setClerkUserId("user_1");
        existingInvoiceOrder.setOrderType("subscription_initial");
        existingInvoiceOrder.setPlanCode("basic_monthly");
        existingInvoiceOrder.setStatus("completed");
        existingInvoiceOrder.setStripeInvoiceId("in_123");
        existingInvoiceOrder.setStripeSubscriptionId("sub_current");

        RechargeOrderEntity stalePendingOrder = new RechargeOrderEntity();
        stalePendingOrder.setId(130L);
        stalePendingOrder.setOrderNo("RO_stale");
        stalePendingOrder.setClerkUserId("user_1");
        stalePendingOrder.setOrderType("subscription_initial");
        stalePendingOrder.setPlanCode("basic_monthly");
        stalePendingOrder.setStatus("pending");
        stalePendingOrder.setStripeSubscriptionId("sub_old");

        assertEquals(
                existingInvoiceOrder,
                StripeBillingWebhookService.selectSubscriptionInvoiceOrder(
                        existingInvoiceOrder,
                        stalePendingOrder,
                        "sub_current"));
    }

    @Test
    void invoicePaidKeepsPendingOrderWhenInvoiceNotSeenYet() {
        RechargeOrderEntity stalePendingOrder = new RechargeOrderEntity();
        stalePendingOrder.setId(130L);
        stalePendingOrder.setOrderNo("RO_pending");
        stalePendingOrder.setClerkUserId("user_1");
        stalePendingOrder.setOrderType("subscription_initial");
        stalePendingOrder.setPlanCode("basic_monthly");
        stalePendingOrder.setStatus("pending");
        stalePendingOrder.setStripeSubscriptionId("sub_current");

        assertEquals(
                stalePendingOrder,
                StripeBillingWebhookService.selectSubscriptionInvoiceOrder(
                        null,
                        stalePendingOrder,
                        "sub_current"));
    }

    @Test
    void paidInvoiceGrantTypeDistinguishesInitialSubscriptionFromRenewal() {
        Invoice initialInvoice = new Invoice();
        initialInvoice.setBillingReason("subscription_create");
        Invoice renewalInvoice = new Invoice();
        renewalInvoice.setBillingReason("subscription_cycle");

        assertEquals("subscription_initial",
                StripeBillingWebhookService.resolvePaidInvoiceGrantType(initialInvoice, null));
        assertEquals("subscription_renewal",
                StripeBillingWebhookService.resolvePaidInvoiceGrantType(renewalInvoice, null));
    }

    @Test
    void stalePaidEventIsIgnoredAfterSubscriptionWasCanceledLater() {
        UserSubscriptionEntity existing = new UserSubscriptionEntity();
        existing.setStatus("canceled");
        existing.setLastSyncedAt(java.time.LocalDateTime.parse("2026-06-27T14:27:48"));

        assertTrue(StripeBillingWebhookService.shouldIgnorePaidInvoiceSync(
                existing,
                "sub_old",
                java.time.LocalDateTime.parse("2026-06-27T14:21:37").toEpochSecond(java.time.ZoneOffset.UTC)));
    }

    @Test
    void subscriptionEventWatermarkRejectsOlderOrDuplicateEventsButNotSameSecondPeers() {
        UserSubscriptionEntity existing = new UserSubscriptionEntity();
        existing.setLastStripeEventCreatedAt(200L);
        existing.setLastStripeEventId("evt_newer");

        assertTrue(StripeBillingWebhookService.isStaleSubscriptionEvent(
                existing, 199L, "evt_older_time"));
        assertFalse(StripeBillingWebhookService.isStaleSubscriptionEvent(
                existing, 200L, "evt_earlier_id"));
        assertTrue(StripeBillingWebhookService.isStaleSubscriptionEvent(
                existing, 200L, "evt_newer"));
        assertFalse(StripeBillingWebhookService.isStaleSubscriptionEvent(
                existing, 200L, "evt_z_later_id"));
        assertFalse(StripeBillingWebhookService.isStaleSubscriptionEvent(
                existing, 201L, "evt_later_time"));
    }

    @Test
    void invoiceFailureDoesNotDowngradeWhenStripeSubscriptionIsActive() throws Exception {
        UserSubscriptionEntity existing = activeSubscription();
        existing.setId(91L);
        existing.setStripeSubscriptionId("sub_recovered");
        when(userSubscriptionMapper.selectOne(any())).thenReturn(existing);
        when(userSubscriptionMapper.selectByUserForUpdate(existing.getClerkUserId()))
                .thenReturn(existing);

        Subscription authoritative = new Subscription();
        authoritative.setId("sub_recovered");
        authoritative.setStatus("active");

        List<Object> updateValues = new ArrayList<>();
        when(userSubscriptionMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<?> wrapper = invocation.getArgument(1);
            updateValues.addAll(wrapper.getParamNameValuePairs().values());
            return 1;
        });

        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                return authoritative;
            }
        };
        Invoice invoice = new Invoice();
        invoice.setId("in_old_failure");

        invokeHandleInvoiceFailed(
                service,
                invoice,
                "invoice.payment_failed",
                "sub_recovered",
                200L,
                "evt_old_failure");

        assertFalse(updateValues.contains("past_due"));
        verify(rechargeOrderMapper, never()).update(isNull(), any());
    }

    @Test
    void invoiceFailureDoesNotReleaseOpenSubscriptionDisputeHold() throws Exception {
        UserSubscriptionEntity existing = activeSubscription();
        existing.setId(92L);
        existing.setStatus("unpaid");
        existing.setStripeSubscriptionId("sub_disputed");
        when(userSubscriptionMapper.selectOne(any())).thenReturn(existing);
        when(userSubscriptionMapper.selectByUserForUpdate(existing.getClerkUserId()))
                .thenReturn(existing);

        RechargeOrderEntity disputed = new RechargeOrderEntity();
        disputed.setOrderType("subscription_renewal");
        disputed.setStatus("disputed");
        disputed.setStripeSubscriptionId("sub_disputed");
        when(rechargeOrderMapper.selectOne(any())).thenReturn(disputed);

        Subscription authoritative = new Subscription();
        authoritative.setId("sub_disputed");
        authoritative.setStatus("past_due");
        List<Object> updateValues = new ArrayList<>();
        when(userSubscriptionMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<?> wrapper = invocation.getArgument(1);
            updateValues.addAll(wrapper.getParamNameValuePairs().values());
            return 1;
        });
        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                return authoritative;
            }
        };
        Invoice invoice = new Invoice();
        invoice.setId("in_failure_during_dispute");

        invokeHandleInvoiceFailed(
                service,
                invoice,
                "invoice.payment_failed",
                "sub_disputed",
                201L,
                "evt_failure_during_dispute");

        assertFalse(updateValues.contains("past_due"));
        assertFalse(updateValues.contains("active"));
    }

    @Test
    void paidInvoiceDoesNotReleaseOpenSubscriptionDisputeHold() throws Exception {
        UserSubscriptionEntity existing = activeSubscription();
        existing.setId(93L);
        existing.setStatus("unpaid");
        existing.setStripeSubscriptionId("sub_disputed");
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(existing);

        RechargeOrderEntity disputed = new RechargeOrderEntity();
        disputed.setOrderType("subscription_renewal");
        disputed.setStatus("disputed");
        disputed.setStripeSubscriptionId("sub_disputed");
        when(rechargeOrderMapper.selectOne(any())).thenReturn(disputed);
        Subscription authoritative = new Subscription();
        authoritative.setId("sub_disputed");
        authoritative.setStatus("active");
        authoritative.setMetadata(Map.of("clerk_user_id", "user_1"));
        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                return authoritative;
            }
        };
        Invoice invoice = new Invoice();
        invoice.setId("in_paid_during_dispute");
        invoice.setSubscription("sub_disputed");

        invokeHandleInvoicePaid(service, invoice, "sub_disputed", 202L);

        verify(billingQuotaGateway, never()).resetFromPaidInvoice(
                any(), any(), any(), any(), any(), any(), any());
        verify(billingQuotaGateway, never()).addFullPlanForUpgrade(
                any(), any(), any(), any(), any(), any());
        verify(billingQuotaGateway, never()).resumeEligibleAddons(
                any(), any(), any());
        verify(userSubscriptionMapper, never()).update(isNull(), any());
    }

    @Test
    void paidInvoiceDoesNotGrantEntitlementsWhenAuthoritativeSubscriptionIsCanceled() throws Exception {
        UserSubscriptionEntity existing = activeSubscription();
        existing.setId(94L);
        existing.setPlanCode("plus_monthly");
        existing.setStripeSubscriptionId("sub_canceled");
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(existing);
        when(rechargeOrderMapper.selectOne(any())).thenReturn(null);
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);

        Subscription authoritative = new Subscription();
        authoritative.setId("sub_canceled");
        authoritative.setStatus("canceled");
        authoritative.setMetadata(Map.of("clerk_user_id", "user_1"));
        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                return authoritative;
            }
        };
        Invoice invoice = new Invoice();
        invoice.setId("in_historical_paid");
        invoice.setSubscription("sub_canceled");

        invokeHandleInvoicePaid(service, invoice, "sub_canceled", 203L);

        verify(billingQuotaGateway, never()).resetFromPaidInvoice(
                any(), any(), any(), any(), any(), any(), any());
        verify(billingQuotaGateway, never()).addFullPlanForUpgrade(
                any(), any(), any(), any(), any(), any());
        verify(billingQuotaGateway, never()).resumeEligibleAddons(
                any(), any(), any());
        verify(billingQuotaGateway).clearPlanQuota(
                "user_1",
                "sub_canceled",
                "plus_monthly",
                "subscription:sub_canceled:deleted");
    }

    @Test
    void subscriptionUpdatedUsesAuthoritativeRemoteCanceledStateAtSameTimestamp() throws Exception {
        UserSubscriptionEntity existing = activeSubscription();
        existing.setId(88L);
        existing.setPlanCode("plus_monthly");
        existing.setTier("plus");
        existing.setLastStripeEventCreatedAt(1781696510L);
        existing.setLastStripeEventId("evt_z_previously_processed");
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(existing);
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);
        List<Object> updateValues = new ArrayList<>();
        when(userSubscriptionMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<?> wrapper = invocation.getArgument(1);
            updateValues.addAll(wrapper.getParamNameValuePairs().values());
            return 1;
        });
        AtomicBoolean retrievedRemote = new AtomicBoolean(false);
        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                retrievedRemote.set(true);
                Subscription remote = new Subscription();
                remote.setId(subscriptionId);
                remote.setCustomer("cus_123");
                remote.setStatus("canceled");
                remote.setMetadata(Map.of("clerk_user_id", "user_1"));
                return remote;
            }
        };
        String json = """
                {"id":"evt_active_same_second","object":"event","created":1781696510,
                 "api_version":"2023-10-16","type":"customer.subscription.updated","data":{"object":{
                   "id":"sub_active","object":"subscription","customer":"cus_123","status":"active",
                   "metadata":{"clerk_user_id":"user_1"},
                   "items":{"object":"list","data":[{"id":"si_1","object":"subscription_item",
                     "price":{"id":"price_plus","object":"price"}}]}}}}
                """;

        invokeSyncSubscriptionEvent(
                service,
                com.stripe.net.ApiResource.GSON.fromJson(json, Event.class),
                false,
                false);

        assertTrue(retrievedRemote.get());
        assertTrue(updateValues.contains("canceled"));
        assertFalse(updateValues.contains("active"));
        verify(billingQuotaGateway).clearPlanQuota(
                "user_1", "sub_active", "plus_monthly", "subscription:sub_active:deleted");
    }

    @Test
    void stalePaidEventIsIgnoredWhenDifferentSubscriptionIsAlreadyCurrent() {
        UserSubscriptionEntity existing = new UserSubscriptionEntity();
        existing.setStatus("active");
        existing.setStripeSubscriptionId("sub_current");

        assertTrue(StripeBillingWebhookService.shouldIgnorePaidInvoiceSync(
                existing,
                "sub_old",
                null));
    }

    @Test
    void currentPaidEventIsNotIgnoredForSameSubscription() {
        UserSubscriptionEntity existing = new UserSubscriptionEntity();
        existing.setStatus("active");
        existing.setStripeSubscriptionId("sub_current");
        existing.setLastSyncedAt(java.time.LocalDateTime.parse("2026-06-27T14:20:00"));

        assertFalse(StripeBillingWebhookService.shouldIgnorePaidInvoiceSync(
                existing,
                "sub_current",
                java.time.LocalDateTime.parse("2026-06-27T14:21:37").toEpochSecond(java.time.ZoneOffset.UTC)));
    }

    @Test
    void supportsOnlyV2CheckoutMetadata() {
        Event addon = event("checkout.session.completed", "checkout.session", "addon");
        Event manualUpgrade = event("checkout.session.completed", "checkout.session", "subscription_upgrade_manual");
        Event legacy = event("checkout.session.completed", "checkout.session", null);

        assertTrue(service().supports(addon));
        assertTrue(service().supports(manualUpgrade));
        assertFalse(service().supports(legacy));
    }

    @Test
    void asyncAddonPaymentSuccessUsesNormalFulfillment() throws Exception {
        when(rechargeOrderMapper.selectOne(any())).thenReturn(addonOrder("cs_test_async_paid"));
        when(userSubscriptionMapper.selectByUser("user_1")).thenReturn(activeSubscription());
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);

        Event event = checkoutEvent(
                "checkout.session.async_payment_succeeded",
                "cs_test_async_paid",
                "paid");

        invokeHandle(service(), event);

        verify(billingQuotaGateway).grantAddonFromCheckout(
                eq("user_1"),
                eq(new AddonGrantSnapshot(42L, "addon_assignment_3", "task_create", 3L, 2)),
                eq("cs_test_async_paid"),
                eq("pi_test_async_paid"),
                any());
    }

    @Test
    void asyncAddonPaymentFailureMarksOrderFailedWithoutGrant() throws Exception {
        Event event = checkoutEvent(
                "checkout.session.async_payment_failed",
                "cs_test_async_failed",
                "unpaid");

        invokeHandle(service(), event);

        verify(rechargeOrderMapper).update(isNull(), any());
        verify(billingQuotaGateway, never()).grantAddonFromCheckout(
                any(), any(AddonGrantSnapshot.class), any(), any(), any());
    }

    @Test
    void paidAddonWithMissingSnapshotIsRefundedWithoutGrant() {
        RechargeOrderEntity order = addonOrder("cs_missing_snapshot");
        order.setValidityMonthsSnapshot(null);
        when(rechargeOrderMapper.selectOne(any())).thenReturn(order);
        AtomicBoolean refunded = new AtomicBoolean(false);
        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            void refundAddonCheckout(String paymentIntentId, String stripeSessionId, String reason) {
                refunded.set(true);
            }
        };
        Event event = checkoutEvent(
                "checkout.session.async_payment_succeeded",
                "cs_missing_snapshot",
                "paid");
        List<Object> orderUpdateValues = new ArrayList<>();
        when(rechargeOrderMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<?> wrapper = invocation.getArgument(1);
            orderUpdateValues.addAll(wrapper.getParamNameValuePairs().values());
            return 1;
        });

        assertDoesNotThrow(() -> invokeHandle(service, event));

        assertTrue(refunded.get());
        assertTrue(orderUpdateValues.contains("refunded"));
        assertTrue(orderUpdateValues.contains("missing_addon_order_snapshot"));
        verify(billingQuotaGateway, never()).grantAddonFromCheckout(
                any(), any(AddonGrantSnapshot.class), any(), any(), any());
    }

    @Test
    void paidAddonWithoutLocalOrderIsRefundedWithoutDeadLetterRetry() {
        AtomicBoolean refunded = new AtomicBoolean(false);
        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            void refundAddonCheckout(String paymentIntentId, String stripeSessionId, String reason) {
                refunded.set(true);
            }
        };
        Event event = checkoutEvent(
                "checkout.session.async_payment_succeeded",
                "cs_missing_order",
                "paid");

        assertDoesNotThrow(() -> invokeHandle(service, event));

        assertTrue(refunded.get());
        verify(billingQuotaGateway, never()).grantAddonFromCheckout(
                any(), any(AddonGrantSnapshot.class), any(), any(), any());
    }

    @Test
    void paymentFailureGraceEndsThreeDaysAfterFailure() {
        LocalDateTime failedAt = LocalDateTime.parse("2026-07-15T10:00:00");

        assertEquals(
                LocalDateTime.parse("2026-07-18T10:00:00"),
                StripeBillingWebhookService.calculateGraceEnd(failedAt, 3));
    }

    @Test
    void addonCheckoutUsesSimulationTimeWhenStripeTestClockIsAhead() throws Exception {
        String originalApiKey = Stripe.apiKey;
        Stripe.apiKey = "sk_test_123";
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);

        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setClerkUserId("user_1");
        subscription.setStripeSubscriptionId("sub_test_clock");
        subscription.setStatus("active");
        when(userSubscriptionMapper.selectByUser("user_1")).thenReturn(subscription);

        when(rechargeOrderMapper.selectOne(any())).thenReturn(addonOrder("cs_addon_1"));

        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                Subscription subscription = new Subscription();
                subscription.setTestClock("clock_123");
                return subscription;
            }

            @Override
            Long retrieveTestClockFrozenTime(String testClockId) {
                return LocalDateTime.parse("2026-07-10T12:00:00")
                        .toEpochSecond(ZoneOffset.UTC);
            }
        };

        Session session = new Session();
        session.setId("cs_addon_1");
        session.setClientReferenceId("user_1");
        session.setPaymentStatus("paid");
        session.setPaymentIntent("pi_addon_1");
        session.setCreated(LocalDateTime.parse("2026-07-01T08:00:00").toEpochSecond(ZoneOffset.UTC));
        session.setMetadata(Map.of(
                "purchase_type", "addon",
                "addon_code", "addon_assignment_3",
                "clerk_user_id", "user_1"
        ));

        try {
            invokeHandleCheckoutCompleted(service, session);

            verify(billingQuotaGateway).grantAddonFromCheckout(
                    eq("user_1"),
                    eq(new AddonGrantSnapshot(42L, "addon_assignment_3", "task_create", 3L, 2)),
                    eq("cs_addon_1"),
                    eq("pi_addon_1"),
                    eq(Instant.parse("2026-07-10T12:00:00Z")));
        } finally {
            Stripe.apiKey = originalApiKey;
        }
    }

    @Test
    void manualUpgradeUsesStripeTestClockAsBillingNow() {
        String originalApiKey = Stripe.apiKey;
        Stripe.apiKey = "sk_test_123";
        Subscription subscription = new Subscription();
        subscription.setTestClock("clock_upgrade");

        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Long retrieveTestClockFrozenTime(String testClockId) {
                return LocalDateTime.parse("2028-07-17T05:20:39")
                        .toEpochSecond(ZoneOffset.UTC);
            }
        };

        try {
            assertEquals(
                    LocalDateTime.parse("2028-07-17T05:20:39"),
                    service.resolveStripeSubscriptionNow(
                            subscription,
                            LocalDateTime.parse("2026-07-18T05:20:39")));
        } finally {
            Stripe.apiKey = originalApiKey;
        }
    }

    @Test
    void resolvesInvoiceSubscriptionIdFromCloverParentDetails() {
        String json = """
                {
                  "id": "evt_test",
                  "object": "event",
                  "api_version": "2025-12-15.clover",
                  "type": "invoice.paid",
                  "data": {
                    "object": {
                      "id": "in_test",
                      "object": "invoice",
                      "subscription": null,
                      "parent": {
                        "subscription_details": {
                          "subscription": "sub_parent"
                        },
                        "type": "subscription_details"
                      },
                      "lines": {
                        "object": "list",
                        "data": [
                          {
                            "id": "il_test",
                            "object": "line_item",
                            "parent": {
                              "subscription_item_details": {
                                "subscription": "sub_line",
                                "subscription_item": "si_test"
                              },
                              "type": "subscription_item_details"
                            }
                          }
                        ]
                      }
                    },
                    "previous_attributes": null
                  }
                }
                """;
        Event event = com.stripe.net.ApiResource.GSON.fromJson(json, Event.class);

        assertEquals("sub_parent", service().resolveInvoiceSubscriptionId(event));
    }

    @Test
    void resolvesSubscriptionPeriodFromCloverSubscriptionItem() {
        String json = """
                {
                  "id": "evt_test",
                  "object": "event",
                  "api_version": "2025-12-15.clover",
                  "type": "customer.subscription.updated",
                  "data": {
                    "object": {
                      "id": "sub_test",
                      "object": "subscription",
                      "items": {
                        "object": "list",
                        "data": [
                          {
                            "id": "si_test",
                            "object": "subscription_item",
                            "current_period_start": 1781696510,
                            "current_period_end": 1813232510
                          }
                        ]
                      },
                      "status": "active"
                    }
                  }
                }
                """;
        Event event = com.stripe.net.ApiResource.GSON.fromJson(json, Event.class);

        assertEquals(1781696510L, service().resolveSubscriptionPeriodStart(event));
        assertEquals(1813232510L, service().resolveSubscriptionPeriodEnd(event));
    }

    @Test
    void resolvesSubscriptionUpdateBillingReasonAsUpgrade() {
        Invoice invoice = new Invoice();
        invoice.setBillingReason("subscription_update");

        assertEquals("subscription_upgrade", service().resolveInvoiceOrderType(invoice));
        assertTrue(service().isSubscriptionUpgradeInvoice(invoice, null));
    }

    @Test
    void clearPendingUpgradeStateResetsPendingPlanCode() {
        UserSubscriptionEntity entity = new UserSubscriptionEntity();
        entity.setId(20L);
        entity.setPendingPlanCode("plus_monthly");
        entity.setPendingUpgradeOrderNo("RO202606230001");

        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setOrderType("subscription_upgrade_manual");

        StripeBillingWebhookService service = service();
        service.clearPendingUpgradeState(entity, order, "invoice.payment_failed");

        assertNull(entity.getPendingPlanCode());
        assertNull(entity.getPendingUpgradeOrderNo());
        verify(userSubscriptionMapper).update(isNull(), any());
    }

    @Test
    void markManualUpgradeOrderSwitching_marksOrderSwitchingInsteadOfPaid() {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(30L);

        StripeBillingWebhookService service = service();
        service.markManualUpgradeOrderSwitching(order, "cs_test_123", "pi_test_123");

        verify(rechargeOrderMapper).update(isNull(), any());
    }

    @Test
    void manualUpgradeSwitchFailurePersistsRetryableOrderWithoutThrowing() {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(31L);
        order.setOrderNo("RO_TEST_CLOCK_FAILURE");
        order.setClerkUserId("user_1");
        order.setPlanCode("plus_monthly");
        order.setTargetPlanCode("pro_monthly");
        order.setStripeSubscriptionId("sub_test_clock");
        order.setUpgradeChargeType("monthly_full");

        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setClerkUserId("user_1");
        current.setStatus("active");
        current.setPlanCode("plus_monthly");
        current.setStripeSubscriptionId("sub_test_clock");
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(current);

        SubscriptionPlanEntity currentPlan = new SubscriptionPlanEntity();
        currentPlan.setPlanCode("plus_monthly");
        currentPlan.setBillingInterval("month");
        SubscriptionPlanEntity targetPlan = new SubscriptionPlanEntity();
        targetPlan.setPlanCode("pro_monthly");
        targetPlan.setBillingInterval("month");
        targetPlan.setStripePriceId("price_pro_monthly");
        when(subscriptionPlanMapper.selectOne(any())).thenReturn(currentPlan, targetPlan);

        Subscription subscription = new Subscription();
        subscription.setId("sub_test_clock");
        SubscriptionItem item = new SubscriptionItem();
        item.setId("si_test_clock");
        item.setQuantity(1L);
        subscription.setItems(new com.stripe.model.SubscriptionItemCollection());
        subscription.getItems().setData(List.of(item));

        List<Object> updateValues = new ArrayList<>();
        when(rechargeOrderMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<?> wrapper = invocation.getArgument(1);
            updateValues.addAll(wrapper.getParamNameValuePairs().values());
            return 1;
        });

        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                return subscription;
            }

            @Override
            Subscription updateStripeSubscription(
                    Subscription source,
                    SubscriptionUpdateParams params,
                    com.stripe.net.RequestOptions options) throws InvalidRequestException {
                throw new InvalidRequestException(
                        "trial_end must be in the future",
                        "trial_end",
                        "req_test_clock",
                        "invalid_request_error",
                        400,
                        null);
            }
        };

        assertFalse(service.attemptManualUpgradeSwitch(
                order,
                "user_1",
                "cs_paid_upgrade",
                "pi_paid_upgrade"));
        assertTrue(updateValues.contains("switch_failed"));
        assertTrue(updateValues.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(value -> value.contains("trial_end must be in the future")));
    }

    @Test
    void paidManualUpgradeWithChangedSourcePlanIsRefundedWithoutSwitching() {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(32L);
        order.setOrderNo("RO_STALE_UPGRADE");
        order.setClerkUserId("user_1");
        order.setPlanCode("plus_monthly");
        order.setTargetPlanCode("pro_monthly");
        order.setStripeSubscriptionId("sub_current");
        order.setStripePaymentIntentId("pi_stale_upgrade");
        order.setBizContext("""
                {
                  "source_invoice_id":"null",
                  "current_net_paid_cents":0,
                  "old_period_start":"2026-07-01T00:00",
                  "old_period_end":"2026-08-01T00:00"
                }
                """);

        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setClerkUserId("user_1");
        current.setStatus("active");
        current.setPlanCode("basic_monthly");
        current.setStripeSubscriptionId("sub_current");
        current.setCurrentPeriodStart(LocalDateTime.parse("2026-07-01T00:00"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2026-08-01T00:00"));
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(current);

        Subscription subscription = new Subscription();
        subscription.setId("sub_current");
        AtomicBoolean refunded = new AtomicBoolean(false);
        AtomicBoolean switched = new AtomicBoolean(false);
        List<Object> updateValues = new ArrayList<>();
        when(rechargeOrderMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<?> wrapper = invocation.getArgument(1);
            updateValues.addAll(wrapper.getParamNameValuePairs().values());
            return 1;
        });

        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                return subscription;
            }

            @Override
            void refundCheckoutPayment(
                    String paymentIntentId,
                    String stripeReferenceId,
                    String reason,
                    String idempotencyPrefix) {
                refunded.set(true);
            }

            @Override
            Subscription updateStripeSubscription(
                    Subscription source,
                    SubscriptionUpdateParams params,
                    com.stripe.net.RequestOptions options) {
                switched.set(true);
                return source;
            }
        };

        assertFalse(service.attemptManualUpgradeSwitch(
                order,
                "user_1",
                "cs_stale_upgrade",
                "pi_stale_upgrade"));
        assertTrue(refunded.get());
        assertFalse(switched.get());
        assertTrue(updateValues.contains("refunded"));
        assertTrue(updateValues.stream().anyMatch(
                value -> value instanceof String text && text.contains("source_plan_changed")));
    }

    @Test
    void paidManualUpgradeWithCorruptQuoteSnapshotIsRefundedWithoutSwitching() {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(34L);
        order.setOrderNo("RO_CORRUPT_UPGRADE_SNAPSHOT");
        order.setClerkUserId("user_1");
        order.setPlanCode("plus_monthly");
        order.setTargetPlanCode("pro_monthly");
        order.setStripeSubscriptionId("sub_current");
        order.setStripePaymentIntentId("pi_corrupt_upgrade");
        order.setBizContext("{\"current_net_paid_cents\":1999}");

        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setClerkUserId("user_1");
        current.setStatus("active");
        current.setPlanCode("plus_monthly");
        current.setStripeSubscriptionId("sub_current");
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(current);

        Subscription subscription = new Subscription();
        subscription.setId("sub_current");
        AtomicBoolean refunded = new AtomicBoolean(false);
        AtomicBoolean switched = new AtomicBoolean(false);
        List<Object> updateValues = new ArrayList<>();
        when(rechargeOrderMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<?> wrapper = invocation.getArgument(1);
            updateValues.addAll(wrapper.getParamNameValuePairs().values());
            return 1;
        });

        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                return subscription;
            }

            @Override
            void refundCheckoutPayment(
                    String paymentIntentId,
                    String stripeReferenceId,
                    String reason,
                    String idempotencyPrefix) {
                refunded.set(true);
            }

            @Override
            Subscription updateStripeSubscription(
                    Subscription source,
                    SubscriptionUpdateParams params,
                    com.stripe.net.RequestOptions options) {
                switched.set(true);
                return source;
            }
        };

        assertFalse(service.attemptManualUpgradeSwitch(
                order,
                "user_1",
                "cs_corrupt_upgrade",
                "pi_corrupt_upgrade"));
        assertTrue(refunded.get());
        assertFalse(switched.get());
        assertTrue(updateValues.contains("refunded"));
        assertTrue(updateValues.stream().anyMatch(
                value -> value instanceof String text && text.contains("quote_snapshot_invalid")));
    }

    @Test
    void paidManualUpgradeDuringDisputeIsRefundedWithoutSwitchingOrQuotaGrant() {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(35L);
        order.setOrderNo("RO_UPGRADE_DURING_DISPUTE");
        order.setClerkUserId("user_1");
        order.setPlanCode("plus_monthly");
        order.setTargetPlanCode("pro_monthly");
        order.setStripeSubscriptionId("sub_disputed");
        order.setStripePaymentIntentId("pi_upgrade_during_dispute");
        order.setBizContext("""
                {
                  "source_invoice_id":"null",
                  "current_net_paid_cents":0,
                  "old_period_start":"2026-07-01T00:00",
                  "old_period_end":"2026-08-01T00:00"
                }
                """);

        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setClerkUserId("user_1");
        current.setStatus("unpaid");
        current.setPlanCode("plus_monthly");
        current.setStripeSubscriptionId("sub_disputed");
        current.setCurrentPeriodStart(LocalDateTime.parse("2026-07-01T00:00"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2026-08-01T00:00"));
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(current);

        Subscription subscription = new Subscription();
        subscription.setId("sub_disputed");
        AtomicBoolean refunded = new AtomicBoolean(false);
        AtomicBoolean switched = new AtomicBoolean(false);
        when(rechargeOrderMapper.update(isNull(), any())).thenReturn(1);
        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                return subscription;
            }

            @Override
            void refundCheckoutPayment(
                    String paymentIntentId,
                    String stripeReferenceId,
                    String reason,
                    String idempotencyPrefix) {
                refunded.set(true);
            }

            @Override
            Subscription updateStripeSubscription(
                    Subscription source,
                    SubscriptionUpdateParams params,
                    com.stripe.net.RequestOptions options) {
                switched.set(true);
                return source;
            }
        };

        assertFalse(service.attemptManualUpgradeSwitch(
                order,
                "user_1",
                "cs_upgrade_during_dispute",
                "pi_upgrade_during_dispute"));
        assertTrue(refunded.get());
        assertFalse(switched.get());
        verify(quotaGatewayProvider, never()).getIfAvailable();
    }

    @Test
    void paidAnnualUpgradeWithChangedSourceInvoiceIsRefundedWithoutSwitching() {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(33L);
        order.setOrderNo("RO_STALE_ANNUAL_UPGRADE");
        order.setClerkUserId("user_1");
        order.setPlanCode("plus_yearly");
        order.setTargetPlanCode("pro_yearly");
        order.setStripeSubscriptionId("sub_annual");
        order.setStripePaymentIntentId("pi_stale_annual_upgrade");
        order.setBizContext("""
                {
                  "source_invoice_id":"in_original_quote",
                  "current_net_paid_cents":11988,
                  "old_period_start":"2026-01-01T00:00",
                  "old_period_end":"2027-01-01T00:00"
                }
                """);

        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setClerkUserId("user_1");
        current.setStatus("active");
        current.setPlanCode("plus_yearly");
        current.setStripeSubscriptionId("sub_annual");
        current.setCurrentPeriodStart(LocalDateTime.parse("2026-01-01T00:00"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2027-01-01T00:00"));
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(current);

        SubscriptionPlanEntity currentPlan = new SubscriptionPlanEntity();
        currentPlan.setPlanCode("plus_yearly");
        currentPlan.setStripePriceId("price_plus_yearly");
        when(subscriptionPlanMapper.selectOne(any())).thenReturn(currentPlan);

        Subscription subscription = new Subscription();
        subscription.setId("sub_annual");
        subscription.setLatestInvoice("in_newer_cycle");
        SubscriptionItem item = new SubscriptionItem();
        Price price = new Price();
        price.setId("price_plus_yearly");
        item.setPrice(price);
        subscription.setItems(new com.stripe.model.SubscriptionItemCollection());
        subscription.getItems().setData(List.of(item));

        AtomicBoolean refunded = new AtomicBoolean(false);
        AtomicBoolean switched = new AtomicBoolean(false);
        List<Object> updateValues = new ArrayList<>();
        when(rechargeOrderMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<?> wrapper = invocation.getArgument(1);
            updateValues.addAll(wrapper.getParamNameValuePairs().values());
            return 1;
        });
        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                return subscription;
            }

            @Override
            void refundCheckoutPayment(
                    String paymentIntentId,
                    String stripeReferenceId,
                    String reason,
                    String idempotencyPrefix) {
                refunded.set(true);
            }

            @Override
            Subscription updateStripeSubscription(
                    Subscription source,
                    SubscriptionUpdateParams params,
                    com.stripe.net.RequestOptions options) {
                switched.set(true);
                return source;
            }
        };

        assertFalse(service.attemptManualUpgradeSwitch(
                order,
                "user_1",
                "cs_stale_annual_upgrade",
                "pi_stale_annual_upgrade"));
        assertTrue(refunded.get());
        assertFalse(switched.get());
        assertTrue(updateValues.stream().anyMatch(
                value -> value instanceof String text && text.contains("source_invoice_changed")));
    }

    @Test
    void resolveMirroredScheduleIdPreservesStripeScheduleUntilPendingPlanActivates() {
        assertEquals(
                "sub_sched_123",
                StripeBillingWebhookService.resolveMirroredScheduleId(
                        "sub_sched_123",
                        false,
                        false,
                        false));
        assertNull(StripeBillingWebhookService.resolveMirroredScheduleId(
                "sub_sched_123",
                true,
                false,
                false));
        assertNull(StripeBillingWebhookService.resolveMirroredScheduleId(
                "sub_sched_123",
                false,
                true,
                true));
    }

    @Test
    void pendingPlanActivationIsNotTreatedAsUpgradeWithoutUpgradeSignals() {
        UserSubscriptionEntity existing = new UserSubscriptionEntity();
        existing.setPlanCode("plus_yearly");
        existing.setPendingPlanCode("basic_yearly");

        Invoice invoice = new Invoice();
        invoice.setBillingReason("subscription_cycle");

        assertFalse(StripeBillingWebhookService.isPendingPlanActivationUpgrade(
                existing,
                "basic_yearly",
                "basic",
                invoice,
                null));
    }

    @Test
    void pendingPlanActivationDowngradeIsNotTreatedAsUpgradeEvenWithSubscriptionUpdateInvoice() {
        UserSubscriptionEntity existing = new UserSubscriptionEntity();
        existing.setTier("plus");
        existing.setPlanCode("plus_monthly");
        existing.setPendingPlanCode("basic_yearly");

        Invoice invoice = new Invoice();
        invoice.setBillingReason("subscription_update");

        assertFalse(StripeBillingWebhookService.isPendingPlanActivationUpgrade(
                existing,
                "basic_yearly",
                "basic",
                invoice,
                null));
    }

    @Test
    void pendingPlanActivationHigherTierIsTreatedAsUpgradeWhenInvoiceSignalsSubscriptionUpdate() {
        UserSubscriptionEntity existing = new UserSubscriptionEntity();
        existing.setTier("basic");
        existing.setPlanCode("basic_monthly");
        existing.setPendingPlanCode("plus_yearly");

        Invoice invoice = new Invoice();
        invoice.setBillingReason("subscription_update");

        assertTrue(StripeBillingWebhookService.isPendingPlanActivationUpgrade(
                existing,
                "plus_yearly",
                "plus",
                invoice,
                null));
    }

    @Test
    void manualUpgradeInvoiceDoesNotGrantUpgradeQuotaAgainAfterCheckoutGrantSucceeded() {
        RechargeOrderEntity manualUpgradeOrder = new RechargeOrderEntity();
        manualUpgradeOrder.setOrderType("subscription_upgrade_manual");
        manualUpgradeOrder.setStatus("completed");

        assertFalse(StripeBillingWebhookService.shouldApplyQuotaGrantForInvoice(true, manualUpgradeOrder));
    }

    @Test
    void manualUpgradeInvoiceStillGrantsQuotaWhenCheckoutGrantPreviouslyFailed() {
        RechargeOrderEntity manualUpgradeOrder = new RechargeOrderEntity();
        manualUpgradeOrder.setOrderType("subscription_upgrade_manual");
        manualUpgradeOrder.setStatus("quota_failed");

        assertTrue(StripeBillingWebhookService.shouldApplyQuotaGrantForInvoice(true, manualUpgradeOrder));
    }

    @Test
    void nonUpgradeInvoiceStillAppliesResetGrantEvenWhenManualUpgradeOrderExists() {
        RechargeOrderEntity manualUpgradeOrder = new RechargeOrderEntity();
        manualUpgradeOrder.setOrderType("subscription_upgrade_manual");
        manualUpgradeOrder.setStatus("completed");

        assertTrue(StripeBillingWebhookService.shouldApplyQuotaGrantForInvoice(false, manualUpgradeOrder));
    }

    @Test
    void annualDiffManualUpgradeKeepsExistingBillingCycle() {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setUpgradeChargeType("annual_diff");

        SubscriptionPlanEntity currentPlan = new SubscriptionPlanEntity();
        currentPlan.setBillingInterval("year");
        SubscriptionPlanEntity targetPlan = new SubscriptionPlanEntity();
        targetPlan.setBillingInterval("year");

        StripeBillingWebhookService.ManualUpgradeSwitchStrategy strategy =
                StripeBillingWebhookService.resolveManualUpgradeSwitchStrategy(
                        order,
                        currentPlan,
                        targetPlan,
                        java.time.LocalDateTime.parse("2026-06-24T12:00:00"));

        assertEquals(StripeBillingWebhookService.ManualUpgradeSwitchMode.KEEP_BILLING_CYCLE, strategy.mode());
        assertNull(strategy.trialEndEpoch());
    }

    @Test
    void historicalAnnualFullManualUpgradeWithoutChargeTypeStillResetsCycle() {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setQuotedAmountCents(23988);

        SubscriptionPlanEntity currentPlan = new SubscriptionPlanEntity();
        currentPlan.setBillingInterval("year");
        SubscriptionPlanEntity targetPlan = new SubscriptionPlanEntity();
        targetPlan.setBillingInterval("year");
        targetPlan.setPriceCents(23988);

        java.time.LocalDateTime now = java.time.LocalDateTime.parse("2026-06-24T12:00:00");
        StripeBillingWebhookService.ManualUpgradeSwitchStrategy strategy =
                StripeBillingWebhookService.resolveManualUpgradeSwitchStrategy(
                        order,
                        currentPlan,
                        targetPlan,
                        now);

        assertEquals(StripeBillingWebhookService.ManualUpgradeSwitchMode.RESET_CYCLE_WITH_TRIAL, strategy.mode());
        assertEquals(now.plusYears(1).toEpochSecond(java.time.ZoneOffset.UTC), strategy.trialEndEpoch());
    }

    @Test
    void monthlyFullManualUpgradeResetsCycleWithOneMonthTrial() {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setUpgradeChargeType("monthly_full");

        SubscriptionPlanEntity currentPlan = new SubscriptionPlanEntity();
        currentPlan.setBillingInterval("month");
        SubscriptionPlanEntity targetPlan = new SubscriptionPlanEntity();
        targetPlan.setBillingInterval("month");

        java.time.LocalDateTime now = java.time.LocalDateTime.parse("2026-06-24T12:00:00");
        StripeBillingWebhookService.ManualUpgradeSwitchStrategy strategy =
                StripeBillingWebhookService.resolveManualUpgradeSwitchStrategy(
                        order,
                        currentPlan,
                        targetPlan,
                        now);

        assertEquals(StripeBillingWebhookService.ManualUpgradeSwitchMode.RESET_CYCLE_WITH_TRIAL, strategy.mode());
        assertEquals(now.plusMonths(1).toEpochSecond(java.time.ZoneOffset.UTC), strategy.trialEndEpoch());
    }

    @Test
    void annualFullManualUpgradeBuildsTrialBasedUpdateWithoutImmediateChargeFlags() {
        SubscriptionItem item = new SubscriptionItem();
        item.setId("si_test");
        item.setQuantity(1L);

        SubscriptionPlanEntity targetPlan = new SubscriptionPlanEntity();
        targetPlan.setStripePriceId("price_plus_yearly");

        SubscriptionUpdateParams params = StripeBillingWebhookService.buildManualUpgradeUpdateParams(
                item,
                targetPlan,
                "user_123",
                new StripeBillingWebhookService.ManualUpgradeSwitchStrategy(
                        StripeBillingWebhookService.ManualUpgradeSwitchMode.RESET_CYCLE_WITH_TRIAL,
                        java.time.LocalDateTime.parse("2027-06-24T12:00:00")
                                .toEpochSecond(java.time.ZoneOffset.UTC)));

        assertEquals(SubscriptionUpdateParams.ProrationBehavior.NONE, params.getProrationBehavior());
        assertTrue(params.toMap().toString().contains("change_type=upgrade"));
        assertTrue(params.toMap().toString().contains("clerk_user_id=user_123"));
        assertEquals(java.time.LocalDateTime.parse("2027-06-24T12:00:00")
                .toEpochSecond(java.time.ZoneOffset.UTC), params.getTrialEnd());
        assertNull(params.getBillingCycleAnchor());
        assertNull(params.getPaymentBehavior());
    }

    @Test
    void checkoutCompletedForUnpaidInitialSubscriptionDoesNotCreatePendingPlanForNewUser() throws Exception {
        when(userSubscriptionMapper.selectOne(any())).thenReturn(null);

        Session session = new Session();
        session.setId("cs_test_unpaid");
        session.setCustomer("cus_123");
        session.setSubscription(null);
        session.setPaymentStatus("unpaid");
        session.setMetadata(java.util.Map.of(
                "purchase_type", "subscription",
                "clerk_user_id", "user_1",
                "plan_code", "basic_monthly"));

        invokeHandleCheckoutCompleted(service(), session);

        ArgumentCaptor<UserSubscriptionEntity> entityCaptor = ArgumentCaptor.forClass(UserSubscriptionEntity.class);
        verify(userSubscriptionMapper).insert(entityCaptor.capture());
        UserSubscriptionEntity inserted = entityCaptor.getValue();
        assertEquals("user_1", inserted.getClerkUserId());
        assertEquals("free", inserted.getTier());
        assertEquals("incomplete", inserted.getStatus());
        assertNull(inserted.getPendingPlanCode());
        verify(userSubscriptionMapper, never()).update(isNull(), any());
    }

    @Test
    void checkoutCompletedForAddonCapturesSuccessAnalytics() throws Exception {
        RechargeOrderEntity order = addonOrder("cs_test_addon_paid");
        order.setFeatureCode("assignment");
        when(rechargeOrderMapper.selectOne(any())).thenReturn(order);
        when(userSubscriptionMapper.selectByUser("user_1")).thenReturn(activeSubscription());
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);

        Session session = new Session();
        session.setId("cs_test_addon_paid");
        session.setPaymentIntent("pi_test_addon_paid");
        session.setPaymentStatus("paid");
        session.setAmountTotal(9900L);
        session.setCurrency("usd");
        session.setCreated(1781696510L);
        session.setMetadata(java.util.Map.of(
                "purchase_type", "addon",
                "clerk_user_id", "user_1",
                "addon_code", "addon_assignment_3"));

        invokeHandleCheckoutCompleted(service(), session);

        verify(analyticsService).capture(eq("user_1"), eq(AnalyticsEvents.PAYMENT_COMPLETED), any());
        verify(analyticsService).capture(eq("user_1"), eq(AnalyticsEvents.BILLING_PAYMENT_SUCCEEDED), any());
        verify(analyticsService, never()).capture(eq("user_1"), eq("recharge_success"), any());
    }

    @Test
    void checkoutCompletedForAddonUsesOrderSnapshotNotMutableCatalog() throws Exception {
        when(rechargeOrderMapper.selectOne(any())).thenReturn(addonOrder("cs_snapshot"));
        when(userSubscriptionMapper.selectByUser("user_1")).thenReturn(activeSubscription());
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);

        Session session = new Session();
        session.setId("cs_snapshot");
        session.setPaymentIntent("pi_snapshot");
        session.setPaymentStatus("paid");
        session.setAmountTotal(9900L);
        session.setCurrency("usd");
        session.setCreated(1781696510L);
        session.setMetadata(Map.of(
                "purchase_type", "addon",
                "clerk_user_id", "user_1",
                "addon_code", "addon_assignment_3"));

        invokeHandleCheckoutCompleted(service(), session);

        verify(billingQuotaGateway).grantAddonFromCheckout(
                eq("user_1"),
                eq(new AddonGrantSnapshot(42L, "addon_assignment_3", "task_create", 3L, 2)),
                eq("cs_snapshot"),
                eq("pi_snapshot"),
                any());
        verify(addonPackageDefMapper, never()).selectOne(any());
    }

    @Test
    void paidAddonCheckoutRefundsWhenSubscriptionBecameIneligibleBeforeFulfillment() throws Exception {
        when(rechargeOrderMapper.selectOne(any())).thenReturn(addonOrder("cs_refund"));
        UserSubscriptionEntity pastDue = activeSubscription();
        pastDue.setStatus("past_due");
        when(userSubscriptionMapper.selectByUser("user_1")).thenReturn(pastDue);
        List<Object> orderUpdateValues = new ArrayList<>();
        when(rechargeOrderMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<?> wrapper = invocation.getArgument(1);
            orderUpdateValues.addAll(wrapper.getParamNameValuePairs().values());
            return 1;
        });
        int[] refundCalls = {0};
        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            void refundIneligibleAddon(String paymentIntentId, String stripeSessionId) {
                assertEquals("pi_refund", paymentIntentId);
                assertEquals("cs_refund", stripeSessionId);
                refundCalls[0]++;
            }
        };

        Session session = new Session();
        session.setId("cs_refund");
        session.setPaymentIntent("pi_refund");
        session.setPaymentStatus("paid");
        session.setAmountTotal(9900L);
        session.setCurrency("usd");
        session.setMetadata(Map.of(
                "purchase_type", "addon",
                "clerk_user_id", "user_1",
                "addon_code", "addon_assignment_3"));

        invokeHandleCheckoutCompleted(service, session);

        assertEquals(1, refundCalls[0]);
        assertTrue(orderUpdateValues.contains("pi_refund"));
        assertTrue(orderUpdateValues.contains("subscription_ineligible_at_fulfillment"));
        verify(billingQuotaGateway, never()).grantAddonFromCheckout(
                any(), any(AddonGrantSnapshot.class), any(), any(), any());
        verify(rechargeOrderMapper).update(isNull(), any());
    }

    @Test
    void chargeRefundedForAutoRefundedAddonDoesNotAdjustMissingGrant() throws Exception {
        RechargeOrderEntity order = addonOrder("cs_auto_refunded");
        order.setStatus("refunded");
        order.setFailureReason("subscription_ineligible_at_fulfillment");
        order.setStripePaymentIntentId("pi_auto_refunded");
        when(rechargeOrderMapper.selectOne(any())).thenReturn(order);

        String json = """
                {"id":"evt_auto_addon_refund","object":"event","created":1781696510,
                 "api_version":"2023-10-16","type":"charge.refunded","data":{"object":{
                   "id":"ch_auto_addon_refund","object":"charge",
                   "payment_intent":"pi_auto_refunded","amount":9900,
                   "amount_refunded":9900,"refunded":true}}}
                """;

        invokeHandle(service(), com.stripe.net.ApiResource.GSON.fromJson(json, Event.class));

        verify(billingQuotaGateway, never()).adjustAddonForRefund(any(), any(), any(Long.class), any(Long.class));
        verify(rechargeOrderMapper, never()).update(isNull(), any());
    }

    @Test
    void checkoutExpiredCapturesFailedAnalytics() throws Exception {
        Session session = new Session();
        session.setId("cs_test_expired");
        session.setPaymentIntent("pi_test_expired");
        session.setAmountTotal(19900L);
        session.setCurrency("usd");
        session.setMetadata(java.util.Map.of(
                "purchase_type", "subscription",
                "clerk_user_id", "user_1",
                "plan_code", "basic_monthly"));

        invokeHandleCheckoutExpired(service(), session);

        verify(analyticsService).capture(eq("user_1"), eq(AnalyticsEvents.BILLING_PAYMENT_FAILED), any());
    }

    @Test
    void invoicePaymentFailedCapturesFailedAnalytics() throws Exception {
        UserSubscriptionEntity entity = new UserSubscriptionEntity();
        entity.setId(88L);
        entity.setClerkUserId("user_1");
        entity.setPlanCode("basic_monthly");
        entity.setStripeSubscriptionId("sub_123");
        when(userSubscriptionMapper.selectOne(any())).thenReturn(entity);
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(entity);

        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(101L);
        order.setClerkUserId("user_1");
        order.setPlanCode("basic_monthly");
        order.setPriceCents(19900);
        order.setCurrency("usd");
        order.setStripeSessionId("cs_invoice_failed");
        when(rechargeOrderMapper.selectOne(any())).thenReturn(order);

        Invoice invoice = new Invoice();
        invoice.setId("in_failed");
        invoice.setPaymentIntent("pi_failed");
        invoice.setCurrency("usd");
        invoice.setSubscription("sub_123");

        StripeBillingWebhookService service = new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager) {
            @Override
            Subscription retrieveStripeSubscription(String subscriptionId) {
                Subscription subscription = new Subscription();
                subscription.setId(subscriptionId);
                subscription.setStatus("past_due");
                return subscription;
            }
        };

        invokeHandleInvoiceFailed(
                service,
                invoice,
                "invoice.payment_failed",
                "sub_123",
                1781696510L,
                "evt_test_invoice_failed");

        verify(analyticsService).capture(eq("user_1"), eq(AnalyticsEvents.BILLING_PAYMENT_FAILED), any());
    }

    @Test
    void checkoutCompletedForUnpaidInitialSubscriptionDoesNotMarkExistingFreeAccountPending() throws Exception {
        UserSubscriptionEntity existing = new UserSubscriptionEntity();
        existing.setId(88L);
        existing.setClerkUserId("user_1");
        existing.setTier("free");
        existing.setStatus("free");

        when(userSubscriptionMapper.selectOne(any())).thenReturn(existing);

        Session session = new Session();
        session.setId("cs_test_unpaid_existing");
        session.setCustomer("cus_456");
        session.setSubscription(null);
        session.setPaymentStatus("unpaid");
        session.setMetadata(java.util.Map.of(
                "purchase_type", "subscription",
                "clerk_user_id", "user_1",
                "plan_code", "plus_monthly"));

        invokeHandleCheckoutCompleted(service(), session);

        ArgumentCaptor<LambdaUpdateWrapper<UserSubscriptionEntity>> updateCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(userSubscriptionMapper).update(isNull(), updateCaptor.capture());
        java.lang.reflect.Field field = updateCaptor.getValue().getClass().getSuperclass().getSuperclass()
                .getDeclaredField("paramNameValuePairs");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> paramValues = (java.util.Map<String, Object>) field.get(updateCaptor.getValue());
        assertFalse(paramValues.containsValue("plus_monthly"));
    }

    @Test
    void resolvePeriodEpochPrefersExplicitOverrideOverSubscriptionValue() {
        assertEquals(200L, StripeBillingWebhookService.resolvePeriodEpoch(200L, 100L));
        assertEquals(100L, StripeBillingWebhookService.resolvePeriodEpoch(null, 100L));
        assertNull(StripeBillingWebhookService.resolvePeriodEpoch(null, null));
    }

    @Test
    void applySubscriptionDeletedClearsResidualSubscriptionState() {
        UserSubscriptionEntity entity = new UserSubscriptionEntity();
        entity.setTier("plus");
        entity.setPlanCode("plus_yearly");
        entity.setStatus("active");
        entity.setStripeCustomerId("cus_123");
        entity.setStripeSubscriptionId("sub_123");
        entity.setStripeScheduleId("sub_sched_123");
        entity.setCurrentPeriodStart(java.time.LocalDateTime.parse("2026-06-23T14:58:10"));
        entity.setCurrentPeriodEnd(java.time.LocalDateTime.parse("2027-06-23T14:58:10"));
        entity.setQuotaPeriodStart(java.time.LocalDateTime.parse("2026-06-23T14:58:10"));
        entity.setQuotaPeriodEnd(java.time.LocalDateTime.parse("2026-07-23T14:58:10"));
        entity.setCancelAtPeriodEnd(true);
        entity.setPendingPlanCode("free");
        entity.setPendingEffectiveAt(java.time.LocalDateTime.parse("2027-06-23T14:58:10"));
        entity.setPendingUpgradeOrderNo("RO202606230001");
        entity.setPendingUpgradeExpiresAt(java.time.LocalDateTime.parse("2026-06-23T15:58:10"));
        entity.setGraceEndAt(java.time.LocalDateTime.parse("2026-06-24T14:58:10"));

        Subscription subscription = new Subscription();
        subscription.setCustomer("cus_123");
        subscription.setId("sub_123");
        subscription.setStatus("canceled");
        subscription.setCurrentPeriodStart(1782226690L);
        subscription.setCurrentPeriodEnd(1813762690L);

        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode("plus_yearly");
        plan.setTier("plus");
        plan.setBillingInterval("year");

        service().applySubscription(
                entity,
                subscription,
                plan,
                true,
                false,
                null,
                null,
                java.time.LocalDateTime.parse("2026-06-23T15:11:00"));

        assertEquals("free", entity.getTier());
        assertNull(entity.getPlanCode());
        assertEquals("canceled", entity.getStatus());
        assertNull(entity.getStripeSubscriptionId());
        assertNull(entity.getStripeScheduleId());
        assertNull(entity.getCurrentPeriodStart());
        assertNull(entity.getCurrentPeriodEnd());
        assertNull(entity.getQuotaPeriodStart());
        assertNull(entity.getQuotaPeriodEnd());
        assertFalse(Boolean.TRUE.equals(entity.getCancelAtPeriodEnd()));
        assertNull(entity.getPendingPlanCode());
        assertNull(entity.getPendingEffectiveAt());
        assertNull(entity.getPendingUpgradeOrderNo());
        assertNull(entity.getPendingUpgradeExpiresAt());
        assertNull(entity.getGraceEndAt());
        assertEquals("cus_123", entity.getStripeCustomerId());
    }

    @Test
    void applyActiveSubscriptionClearsPreviousPaymentFailureGrace() {
        UserSubscriptionEntity entity = new UserSubscriptionEntity();
        entity.setGraceEndAt(LocalDateTime.parse("2026-07-18T10:00:00"));

        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode("basic_monthly");
        plan.setTier("basic");
        plan.setBillingInterval("month");

        Subscription subscription = new Subscription();
        subscription.setId("sub_123");
        subscription.setCustomer("cus_123");
        subscription.setStatus("active");
        subscription.setCurrentPeriodStart(1781696510L);
        subscription.setCurrentPeriodEnd(1784288510L);

        service().applySubscription(
                entity,
                subscription,
                plan,
                false,
                false,
                null,
                null,
                LocalDateTime.parse("2026-07-15T10:00:00"));

        assertNull(entity.getGraceEndAt());
    }

    @Test
    void applySubscriptionDeletedWithDeferredPlanDoesNotRequireResolvedPlan() {
        UserSubscriptionEntity entity = new UserSubscriptionEntity();
        entity.setTier("pro");
        entity.setPlanCode("pro_monthly");
        entity.setStatus("active");
        entity.setStripeCustomerId("cus_123");
        entity.setStripeSubscriptionId("sub_123");
        entity.setStripeScheduleId("sub_sched_123");
        entity.setPendingPlanCode("plus_yearly");
        entity.setPendingEffectiveAt(java.time.LocalDateTime.parse("2026-07-23T14:58:10"));

        Subscription subscription = new Subscription();
        subscription.setCustomer("cus_123");
        subscription.setId("sub_123");
        subscription.setStatus("canceled");

        service().applySubscription(
                entity,
                subscription,
                null,
                true,
                false,
                null,
                null,
                java.time.LocalDateTime.parse("2026-06-23T15:11:00"));

        assertEquals("free", entity.getTier());
        assertNull(entity.getPlanCode());
        assertEquals("canceled", entity.getStatus());
        assertNull(entity.getStripeSubscriptionId());
        assertNull(entity.getStripeScheduleId());
        assertNull(entity.getPendingPlanCode());
        assertNull(entity.getPendingEffectiveAt());
    }

    @Test
    void applySubscriptionClearsStaleDeferredPlanWhenStripeAlreadySwitchedToAnotherPlan() {
        UserSubscriptionEntity entity = new UserSubscriptionEntity();
        entity.setTier("basic");
        entity.setPlanCode("basic_monthly");
        entity.setStatus("active");
        entity.setStripeCustomerId("cus_123");
        entity.setStripeSubscriptionId("sub_123");
        entity.setStripeScheduleId("sub_sched_basic_yearly");
        entity.setPendingPlanCode("basic_yearly");
        entity.setPendingEffectiveAt(java.time.LocalDateTime.parse("2026-07-23T14:58:10"));

        Subscription subscription = new Subscription();
        subscription.setCustomer("cus_123");
        subscription.setId("sub_123");
        subscription.setStatus("active");
        subscription.setCurrentPeriodStart(1782226690L);
        subscription.setCurrentPeriodEnd(1813762690L);
        subscription.setSchedule(null);

        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode("plus_yearly");
        plan.setTier("plus");
        plan.setBillingInterval("year");

        service().applySubscription(
                entity,
                subscription,
                plan,
                false,
                false,
                null,
                null,
                java.time.LocalDateTime.parse("2026-06-23T15:11:00"));

        assertEquals("plus", entity.getTier());
        assertEquals("plus_yearly", entity.getPlanCode());
        assertNull(entity.getStripeScheduleId());
        assertNull(entity.getPendingPlanCode());
        assertNull(entity.getPendingEffectiveAt());
    }

    @Test
    void applyScheduleStateRefreshesPendingPlanFromActiveScheduleMetadata() {
        UserSubscriptionEntity entity = new UserSubscriptionEntity();
        entity.setTier("pro");
        entity.setPlanCode("pro_monthly");
        entity.setStatus("active");
        entity.setStripeCustomerId("cus_123");
        entity.setStripeSubscriptionId("sub_123");
        entity.setStripeScheduleId("sub_sched_basic_yearly");
        entity.setPendingPlanCode("plus_yearly");
        entity.setPendingEffectiveAt(java.time.LocalDateTime.parse("2026-07-23T14:58:10"));

        SubscriptionSchedule schedule = new SubscriptionSchedule();
        schedule.setId("sub_sched_basic_yearly");
        schedule.setStatus("active");
        schedule.setMetadata(java.util.Map.of("pending_plan_code", "basic_yearly"));

        service().applyScheduleState(
                entity,
                schedule,
                java.time.LocalDateTime.parse("2026-06-23T15:11:00"));

        assertEquals("sub_sched_basic_yearly", entity.getStripeScheduleId());
        assertEquals("basic_yearly", entity.getPendingPlanCode());
        assertEquals(java.time.LocalDateTime.parse("2026-07-23T14:58:10"), entity.getPendingEffectiveAt());
    }

    @Test
    void applySubscriptionDeletedPreservesExistingCustomerWhenDeletedPayloadHasNoCustomer() {
        UserSubscriptionEntity entity = new UserSubscriptionEntity();
        entity.setTier("basic");
        entity.setPlanCode("basic_yearly");
        entity.setStatus("active");
        entity.setStripeCustomerId("cus_existing");
        entity.setStripeSubscriptionId("sub_existing");

        Subscription subscription = new Subscription();
        subscription.setCustomer(null);
        subscription.setId("sub_existing");
        subscription.setStatus("canceled");

        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode("basic_yearly");
        plan.setTier("basic");
        plan.setBillingInterval("year");

        service().applySubscription(
                entity,
                subscription,
                plan,
                true,
                false,
                null,
                null,
                java.time.LocalDateTime.parse("2026-06-23T15:11:00"));

        assertEquals("cus_existing", entity.getStripeCustomerId());
        assertNull(entity.getStripeSubscriptionId());
    }

    @Test
    void subscriptionDeletedPassesPreviousPlanCodeToClearPlanQuota() throws Exception {
        UserSubscriptionEntity existing = new UserSubscriptionEntity();
        existing.setId(88L);
        existing.setClerkUserId("user_1");
        existing.setTier("basic");
        existing.setPlanCode("basic_monthly");
        existing.setStatus("active");
        existing.setStripeCustomerId("cus_123");
        existing.setStripeSubscriptionId("sub_123");

        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(existing);
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);

        Subscription subscription = new Subscription();
        subscription.setId("sub_123");
        subscription.setCustomer("cus_123");
        subscription.setStatus("canceled");
        subscription.setMetadata(java.util.Map.of("clerk_user_id", "user_1"));

        invokeSyncSubscription(service(), subscription, true, false);

        verify(billingQuotaGateway).clearPlanQuota(
                "user_1",
                "sub_123",
                "basic_monthly",
                "subscription:sub_123:deleted");
    }

    private void assertReviewRequired(Event event) {
        StripeWebhookEventEntity received = webhookEvent(event, "received");
        StripeWebhookEventEntity processing = webhookEvent(event, "processing");
        processing.setAttemptCount(1);
        when(webhookEventMapper.selectById(event.getId()))
                .thenReturn(null, received, processing);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        List<Object> updateValues = new ArrayList<>();
        when(webhookEventMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<?> wrapper = invocation.getArgument(1);
            updateValues.addAll(wrapper.getParamNameValuePairs().values());
            return 1;
        });

        assertDoesNotThrow(() -> service().process(event));
        assertTrue(updateValues.contains("review_required"));
        assertFalse(updateValues.contains("failed"));
        assertFalse(updateValues.contains("dead_letter"));
    }

    private StripeWebhookEventEntity webhookEvent(Event event, String status) {
        StripeWebhookEventEntity entity = new StripeWebhookEventEntity();
        entity.setEventId(event.getId());
        entity.setEventType(event.getType());
        entity.setStatus(status);
        entity.setAttemptCount(0);
        entity.setPayloadJson(com.stripe.net.ApiResource.GSON.toJson(event));
        entity.setNextRetryAt(LocalDateTime.now().minusMinutes(1));
        return entity;
    }

    private Event reversalEvent(
            String eventId,
            String eventType,
            String objectType,
            String objectFields) {
        String json = """
                {"id":"%s","object":"event","created":1781696510,
                 "api_version":"2023-10-16","type":"%s","data":{"object":{
                   "id":"obj_external","object":"%s",%s}}}
                """.formatted(eventId, eventType, objectType, objectFields);
        return com.stripe.net.ApiResource.GSON.fromJson(json, Event.class);
    }

    private StripeBillingWebhookService service() {
        return new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                analyticsService,
                quotaGatewayProvider,
                billingRobotNotifyGatewayProvider,
                transactionManager);
    }

    private void invokeHandleCheckoutCompleted(StripeBillingWebhookService service, Session session) throws Exception {
        var method = StripeBillingWebhookService.class.getDeclaredMethod(
                "handleCheckoutCompleted", String.class, String.class, Session.class);
        method.setAccessible(true);
        method.invoke(service, "evt_test_checkout_completed", "checkout.session.completed", session);
    }

    private void invokeHandle(StripeBillingWebhookService service, Event event) throws Exception {
        var method = StripeBillingWebhookService.class.getDeclaredMethod("handle", Event.class);
        method.setAccessible(true);
        method.invoke(service, event);
    }

    private boolean invokeClaim(StripeBillingWebhookService service, String eventId) throws Exception {
        var method = StripeBillingWebhookService.class.getDeclaredMethod("claim", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, eventId);
    }

    private void invokeHandleCheckoutExpired(StripeBillingWebhookService service, Session session) throws Exception {
        var method = StripeBillingWebhookService.class.getDeclaredMethod(
                "handleCheckoutExpired", String.class, String.class, Session.class);
        method.setAccessible(true);
        method.invoke(service, "evt_test_checkout_expired", "checkout.session.expired", session);
    }

    private void invokeHandleInvoiceFailed(
            StripeBillingWebhookService service,
            Invoice invoice,
            String eventType,
            String eventSubscriptionId,
            Long eventCreated,
            String eventId) throws Exception {
        var method = StripeBillingWebhookService.class.getDeclaredMethod(
                "handleInvoiceFailed",
                String.class,
                Invoice.class,
                String.class,
                String.class,
                Long.class);
        method.setAccessible(true);
        method.invoke(service, eventId, invoice, eventType, eventSubscriptionId, eventCreated);
    }

    private void invokeHandleInvoicePaid(
            StripeBillingWebhookService service,
            Invoice invoice,
            String eventSubscriptionId,
            Long eventCreated) throws Exception {
        var method = StripeBillingWebhookService.class.getDeclaredMethod(
                "handleInvoicePaid",
                Invoice.class,
                String.class,
                Long.class);
        method.setAccessible(true);
        method.invoke(service, invoice, eventSubscriptionId, eventCreated);
    }

    private void invokeSyncSubscription(
            StripeBillingWebhookService service,
            Subscription subscription,
            boolean deleted,
            boolean activatePendingPlan) throws Exception {
        var method = StripeBillingWebhookService.class.getDeclaredMethod(
                "syncSubscription",
                Subscription.class,
                boolean.class,
                boolean.class);
        method.setAccessible(true);
        method.invoke(service, subscription, deleted, activatePendingPlan);
    }

    private void invokeSyncSubscriptionEvent(
            StripeBillingWebhookService service,
            Event event,
            boolean deleted,
            boolean activatePendingPlan) throws Exception {
        var method = StripeBillingWebhookService.class.getDeclaredMethod(
                "syncSubscription",
                Event.class,
                boolean.class,
                boolean.class);
        method.setAccessible(true);
        method.invoke(service, event, deleted, activatePendingPlan);
    }

    private Event event(String type, String object, String purchaseType) {
        String metadata = purchaseType == null
                ? "{}"
                : "{\"purchase_type\":\"" + purchaseType + "\"}";
        String json = """
                {
                  "id":"evt_test",
                  "object":"event",
                  "api_version":"2023-10-16",
                  "type":"%s",
                  "data":{"object":{"id":"obj_test","object":"%s","metadata":%s}}
                }
                """.formatted(type, object, metadata);
        return com.stripe.net.ApiResource.GSON.fromJson(json, Event.class);
    }

    private Event checkoutEvent(String type, String sessionId, String paymentStatus) {
        String json = """
                {
                  "id":"evt_test_async",
                  "object":"event",
                  "created":1781696510,
                  "api_version":"2023-10-16",
                  "type":"%s",
                  "data":{"object":{
                    "id":"%s",
                    "object":"checkout.session",
                    "created":1781696510,
                    "payment_status":"%s",
                    "payment_intent":"pi_test_async_paid",
                    "amount_total":9900,
                    "currency":"usd",
                    "metadata":{
                      "purchase_type":"addon",
                      "clerk_user_id":"user_1",
                      "addon_code":"addon_assignment_3"
                    }
                  }}
                }
                """.formatted(type, sessionId, paymentStatus);
        return com.stripe.net.ApiResource.GSON.fromJson(json, Event.class);
    }

    private RechargeOrderEntity addonOrder(String stripeSessionId) {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(42L);
        order.setOrderType("addon");
        order.setClerkUserId("user_1");
        order.setAddonCode("addon_assignment_3");
        order.setFeatureCode("task_create");
        order.setQuotaAmount(3L);
        order.setValidityMonthsSnapshot(2);
        order.setPriceCents(9900);
        order.setCurrency("usd");
        order.setStripeSessionId(stripeSessionId);
        order.setStatus("pending");
        return order;
    }

    private UserSubscriptionEntity activeSubscription() {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setClerkUserId("user_1");
        subscription.setStripeSubscriptionId("sub_active");
        subscription.setStatus("active");
        return subscription;
    }
}
