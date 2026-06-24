package com.studyagent.infra.service.billing;

import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.param.SubscriptionUpdateParams;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
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
    private ObjectProvider<BillingQuotaGateway> quotaGatewayProvider;
    @Mock
    private PlatformTransactionManager transactionManager;

    @Test
    void supportsSubscriptionLifecycleEvents() {
        Event event = event("invoice.paid", "invoice", null);
        assertTrue(service().supports(event));
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

    private StripeBillingWebhookService service() {
        return new StripeBillingWebhookService(
                webhookEventMapper,
                userSubscriptionMapper,
                subscriptionPlanMapper,
                addonPackageDefMapper,
                rechargeOrderMapper,
                quotaGatewayProvider,
                transactionManager);
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
