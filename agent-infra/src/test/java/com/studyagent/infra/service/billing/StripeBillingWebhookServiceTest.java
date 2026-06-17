package com.studyagent.infra.service.billing;

import com.stripe.model.Event;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.StripeWebhookEventMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.billing.BillingQuotaGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class StripeBillingWebhookServiceTest {
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
        Event legacy = event("checkout.session.completed", "checkout.session", null);

        assertTrue(service().supports(addon));
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
