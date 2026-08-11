package com.studyagent.api.controller;

import com.studyagent.api.service.robot.RobotNotifyAsyncService;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.infra.service.QuotaRechargeService;
import com.studyagent.infra.service.billing.BillingBusinessMetrics;
import com.studyagent.infra.service.billing.StripeBillingWebhookService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebhookControllerMetricsTest {

    @Test
    void invalidSignatureRecordsOneFailureWithoutDynamicLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WebhookController controller = new WebhookController(
                mock(QuotaRechargeService.class),
                mock(AnalyticsService.class),
                mock(RobotNotifyAsyncService.class),
                mock(StripeBillingWebhookService.class),
                new BillingBusinessMetrics(registry));
        ReflectionTestUtils.setField(controller, "webhookSecret", "whsec_test_secret");
        ReflectionTestUtils.setField(controller, "allowUnsignedWebhooks", false);

        var response = controller.stripeWebhook(
                "{\"id\":\"evt_bad_signature\",\"type\":\"invoice.paid\"}",
                "t=1,v1=invalid");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(registry.get("billing.stripe.signature.failure").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("billing.stripe.signature.failure").counter().getId().getTags()).isEmpty();
    }
}
