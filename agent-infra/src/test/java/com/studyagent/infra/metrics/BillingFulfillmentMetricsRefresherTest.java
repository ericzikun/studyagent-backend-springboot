package com.studyagent.infra.metrics;

import com.studyagent.infra.mapper.BillingEntitlementFulfillmentMapper;
import com.studyagent.infra.mapper.BillingFulfillmentOpenAggregate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BillingFulfillmentMetricsRefresherTest {

    @Test
    void refreshesPendingAndFailedGaugesAndResetsMissingSeriesToZero() {
        BillingEntitlementFulfillmentMapper mapper = mock(BillingEntitlementFulfillmentMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BillingFulfillmentMetricsRefresher refresher =
                new BillingFulfillmentMetricsRefresher(mapper, registry);
        when(mapper.selectOpenAggregates()).thenReturn(List.of(
                aggregate("addon", "addon_assignment_3", "pending", 2L, 120),
                aggregate("subscription_renewal", "plus_monthly", "failed", 1L, 300)), List.of());

        refresher.refresh();

        assertThat(gauge(registry, "billing.entitlement.unfulfilled", "addon", "addon_assignment_3", "pending"))
                .isEqualTo(2.0);
        assertThat(gauge(registry, "billing.entitlement.unfulfilled.oldest.age.seconds",
                "subscription_renewal", "plus_monthly", "failed")).isGreaterThanOrEqualTo(299.0);

        refresher.refresh();

        assertThat(gauge(registry, "billing.entitlement.unfulfilled", "addon", "addon_assignment_3", "pending"))
                .isZero();
    }

    @Test
    void failedRefreshKeepsPreviousValuesAndCountsError() {
        BillingEntitlementFulfillmentMapper mapper = mock(BillingEntitlementFulfillmentMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BillingFulfillmentMetricsRefresher refresher =
                new BillingFulfillmentMetricsRefresher(mapper, registry);
        when(mapper.selectOpenAggregates()).thenReturn(List.of(
                aggregate("addon", "addon_assignment_3", "pending", 1L, 60)))
                .thenThrow(new IllegalStateException("database unavailable"));
        refresher.refresh();

        refresher.refresh();

        assertThat(gauge(registry, "billing.entitlement.unfulfilled", "addon", "addon_assignment_3", "pending"))
                .isEqualTo(1.0);
        assertThat(registry.get("studyagent.metrics.refresh")
                .tags("scope", "billing_fulfillment", "result", "error")
                .counter().count()).isEqualTo(1.0);
    }

    private BillingFulfillmentOpenAggregate aggregate(
            String purchaseType,
            String productCode,
            String state,
            long count,
            long ageSeconds) {
        return new BillingFulfillmentOpenAggregate(
                purchaseType,
                productCode,
                state,
                count,
                LocalDateTime.now().minusSeconds(ageSeconds));
    }

    private double gauge(
            SimpleMeterRegistry registry,
            String name,
            String purchaseType,
            String productCode,
            String state) {
        return registry.get(name)
                .tags("purchase_type", purchaseType, "product_code", productCode, "state", state)
                .gauge().value();
    }
}
