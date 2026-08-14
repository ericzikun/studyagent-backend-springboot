package com.studyagent.infra.metrics;

import com.stripe.exception.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalDependencyMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ExternalDependencyMetrics metrics = new ExternalDependencyMetrics(meterRegistry);

    @Test
    void records_only_fixed_tags_for_success_timeout_429_and_5xx() {
        metrics.success(metrics.start(), ExternalDependencyMetrics.Dependency.STRIPE,
                ExternalDependencyMetrics.Operation.CHECKOUT_CREATE);
        metrics.error(metrics.start(), ExternalDependencyMetrics.Dependency.STRIPE,
                ExternalDependencyMetrics.Operation.CHECKOUT_CREATE, new TimeoutException("hidden"));
        metrics.error(metrics.start(), ExternalDependencyMetrics.Dependency.STRIPE,
                ExternalDependencyMetrics.Operation.CHECKOUT_CREATE,
                new ApiException("hidden", "req_hidden", null, 429, null));
        metrics.error(metrics.start(), ExternalDependencyMetrics.Dependency.CLERK,
                ExternalDependencyMetrics.Operation.REMOTE_API,
                WebClientResponseException.create(HttpStatus.BAD_GATEWAY.value(), "hidden", HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8));

        assertCount("stripe", "checkout_create", "success", "none", 1.0);
        assertCount("stripe", "checkout_create", "error", "timeout", 1.0);
        assertCount("stripe", "checkout_create", "error", "stripe_429", 1.0);
        assertCount("clerk", "remote_api", "error", "clerk_5xx", 1.0);
        assertEquals(4, meterRegistry.find("studyagent.external.request.duration").timers().size());
    }

    private void assertCount(String dependency, String operation, String result, String errorType, double expected) {
        assertEquals(expected, meterRegistry.get("studyagent.external.requests")
                .tags("dependency", dependency, "operation", operation,
                        "result", result, "error_type", errorType)
                .counter().count());
    }
}
