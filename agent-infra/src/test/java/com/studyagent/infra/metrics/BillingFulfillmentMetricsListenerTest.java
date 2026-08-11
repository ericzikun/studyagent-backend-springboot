package com.studyagent.infra.metrics;

import com.studyagent.infra.service.billing.BillingEntitlementFulfilledEvent;
import com.studyagent.infra.service.billing.BillingPaymentAcceptedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BillingFulfillmentMetricsListenerTest {

    @Test
    void recordsPaymentAndWholeFulfillmentCountersWithSharedLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BillingFulfillmentMetricsListener listener = new BillingFulfillmentMetricsListener(registry);

        listener.onPaymentAccepted(new BillingPaymentAcceptedEvent(
                "subscription_renewal", "plus_monthly", "success"));
        listener.onFulfillment(new BillingEntitlementFulfilledEvent(
                "subscription_renewal", "plus_monthly", "success"));

        assertThat(registry.get("billing.payment")
                .tags("purchase_type", "subscription_renewal", "product_code", "plus_monthly", "result", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("billing.entitlement.fulfillment")
                .tags("purchase_type", "subscription_renewal", "product_code", "plus_monthly", "result", "success")
                .counter().count()).isEqualTo(1.0);
    }
}
