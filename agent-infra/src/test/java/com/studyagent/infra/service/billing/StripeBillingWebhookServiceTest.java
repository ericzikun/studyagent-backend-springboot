package com.studyagent.infra.service.billing;

import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.studyagent.infra.entity.RechargeOrderEntity;
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
                invoice,
                null));
    }

    @Test
    void resolvePeriodEpochPrefersExplicitOverrideOverSubscriptionValue() {
        assertEquals(200L, StripeBillingWebhookService.resolvePeriodEpoch(200L, 100L));
        assertEquals(100L, StripeBillingWebhookService.resolvePeriodEpoch(null, 100L));
        assertNull(StripeBillingWebhookService.resolvePeriodEpoch(null, null));
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
