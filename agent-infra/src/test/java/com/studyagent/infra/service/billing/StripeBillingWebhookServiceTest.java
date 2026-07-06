package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyagent.common.analytics.AnalyticsEvents;
import com.studyagent.common.analytics.AnalyticsService;
import com.stripe.Stripe;
import com.stripe.model.checkout.Session;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionSchedule;
import com.stripe.param.SubscriptionUpdateParams;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.StripeWebhookEventMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper;
import com.studyagent.service.domain.billing.BillingQuotaGateway;
import com.studyagent.service.domain.billing.BillingRobotNotifyGateway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void supportsSubscriptionLifecycleEvents() {
        Event event = event("invoice.paid", "invoice", null);
        assertTrue(service().supports(event));
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
    void addonCheckoutUsesSimulationTimeWhenStripeTestClockIsAhead() throws Exception {
        String originalApiKey = Stripe.apiKey;
        Stripe.apiKey = "sk_test_123";
        when(quotaGatewayProvider.getIfAvailable()).thenReturn(billingQuotaGateway);

        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setClerkUserId("user_1");
        subscription.setStripeSubscriptionId("sub_test_clock");
        when(userSubscriptionMapper.selectByUser("user_1")).thenReturn(subscription);

        AddonPackageDefEntity addon = new AddonPackageDefEntity();
        addon.setAddonCode("addon_assignment_3");
        addon.setFeatureCode("task_create");
        addon.setQuotaAmount(3L);
        when(addonPackageDefMapper.selectOne(any())).thenReturn(addon);

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
                    eq("addon_assignment_3"),
                    eq("cs_addon_1"),
                    eq("pi_addon_1"),
                    eq(Instant.parse("2026-07-10T12:00:00Z")));
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
        AddonPackageDefEntity addon = new AddonPackageDefEntity();
        addon.setAddonCode("addon_assignment_3");
        addon.setFeatureCode("assignment");
        addon.setQuotaAmount(3L);
        when(addonPackageDefMapper.selectOne(any())).thenReturn(addon);
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
        verify(analyticsService).capture(eq("user_1"), eq(AnalyticsEvents.RECHARGE_SUCCESS), any());
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

        invokeHandleInvoiceFailed(service(), invoice, "invoice.payment_failed", "sub_123");

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

        when(userSubscriptionMapper.selectOne(any())).thenReturn(existing);
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
            String eventSubscriptionId) throws Exception {
        var method = service.getClass().getDeclaredMethod(
                "handleInvoiceFailed",
                String.class,
                Invoice.class,
                String.class,
                String.class);
        method.setAccessible(true);
        method.invoke(service, "evt_test_invoice_failed", invoice, eventType, eventSubscriptionId);
    }

    private void invokeSyncSubscription(
            StripeBillingWebhookService service,
            Subscription subscription,
            boolean deleted,
            boolean activatePendingPlan) throws Exception {
        var method = service.getClass().getDeclaredMethod(
                "syncSubscription",
                Subscription.class,
                boolean.class,
                boolean.class);
        method.setAccessible(true);
        method.invoke(service, subscription, deleted, activatePendingPlan);
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
}
