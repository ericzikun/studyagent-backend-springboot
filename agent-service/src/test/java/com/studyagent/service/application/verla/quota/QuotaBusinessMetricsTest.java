package com.studyagent.service.application.verla.quota;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuotaBusinessMetricsTest {

    @Test
    void recordsOnlyFrozenFeatureAndOutcomeLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QuotaBusinessMetrics metrics = new QuotaBusinessMetrics(registry);

        metrics.recordConsume("task_create", QuotaBusinessMetrics.Result.SUCCESS);
        metrics.recordRefund("task_create", "assignment_failed", QuotaBusinessMetrics.Result.ERROR);
        metrics.recordGrant(
                "subscription_renewal", "task_create", "subscription_renewal", "plus_monthly",
                QuotaBusinessMetrics.Result.SUCCESS);
        metrics.recordGrant(
                "manual_upgrade", "humanizer", "subscription_upgrade", "pro_monthly",
                QuotaBusinessMetrics.Result.SUCCESS);

        assertThat(registry.get("quota.consume")
                .tags("feature", "assignment", "result", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("quota.refund")
                .tags("feature", "assignment", "trigger", "agent_failed", "result", "error")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("billing.quota.grant")
                .tags(
                        "grant_type", "renewal",
                        "feature", "assignment",
                        "purchase_type", "subscription_renewal",
                        "product_code", "plus_monthly",
                        "result", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("billing.quota.grant")
                .tags(
                        "grant_type", "manual_upgrade",
                        "feature", "humanizer",
                        "purchase_type", "subscription_upgrade",
                        "product_code", "pro_monthly",
                        "result", "success")
                .counter().count()).isEqualTo(1.0);
    }
}
