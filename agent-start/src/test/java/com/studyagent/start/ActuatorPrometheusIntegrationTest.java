package com.studyagent.start;

import com.studyagent.api.config.VerlaMetricsConfig;
import com.studyagent.api.controller.verla.VerlaConversationController;
import com.studyagent.api.controller.verla.VerlaInternalController;
import com.studyagent.infra.service.billing.BillingBusinessMetrics;
import com.studyagent.service.application.verla.quota.QuotaBusinessMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = ActuatorPrometheusIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "management.health.db.enabled=false",
        "management.health.redis.enabled=false",
        "management.health.rabbit.enabled=false"
    }
)
class ActuatorPrometheusIntegrationTest {

    private static final Pattern LE_LABEL = Pattern.compile("le=\"([^\"]+)\"");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void exposesOnlySanitizedHealthAndPrometheusMetrics() {
        ResponseEntity<String> ping = restTemplate.getForEntity("/test/ping", String.class);
        assertThat(ping.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/v1/quota/balance", String.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/v1/quota/balance?probe=invalid", String.class).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(restTemplate.getForEntity("/v1/quota/balance?fail=true", String.class).getStatusCode())
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(restTemplate.getForEntity("/v1/internal/verla/sessions/301/context", String.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/v1/internal/verla/conversations/101/context", String.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/v1/verla/conversations/vc_101/assignment/runtime-snapshot", String.class)
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/v1/verla/conversations/vc_101/ai-writing/runtime-snapshot?fail=invalid", String.class)
            .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(restTemplate.getForEntity("/v1/verla/conversations/vc_101/ai-writing/runtime-snapshot?fail=true", String.class)
            .getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        Timer.builder("billing.checkout.duration")
            .register(meterRegistry)
            .record(Duration.ofMillis(25));
        BillingBusinessMetrics billingMetrics = new BillingBusinessMetrics(meterRegistry);
        billingMetrics.recordCheckout(
            billingMetrics.start(),
            BillingBusinessMetrics.CheckoutPurchaseType.SUBSCRIPTION,
            BillingBusinessMetrics.Result.SUCCESS,
            BillingBusinessMetrics.ErrorType.NONE);
        billingMetrics.recordWebhook(
            billingMetrics.start(),
            BillingBusinessMetrics.WebhookEventType.INVOICE_FAILED,
            BillingBusinessMetrics.Result.SUCCESS,
            BillingBusinessMetrics.ErrorType.NONE);
        billingMetrics.recordSignatureFailure();
        billingMetrics.recordUnknownPrice(BillingBusinessMetrics.UnknownPricePurchaseType.SUBSCRIPTION);
        QuotaBusinessMetrics quotaMetrics = new QuotaBusinessMetrics(meterRegistry);
        quotaMetrics.recordConsume("task_create", QuotaBusinessMetrics.Result.SUCCESS);
        quotaMetrics.recordRefund("task_create", "agent_failed", QuotaBusinessMetrics.Result.ERROR);
        quotaMetrics.recordGrant(
            "subscription_initial", "task_create", "subscription_initial", "basic_monthly",
            QuotaBusinessMetrics.Result.SUCCESS);
        Timer.builder("verla.upload.duration")
            .register(meterRegistry)
            .record(Duration.ofSeconds(12));

        assertThat(containsPrometheusRegistry(meterRegistry))
            .as("runtime registry should contain Prometheus but was %s", describeRegistry(meterRegistry))
            .isTrue();
        assertThat(meterRegistry).isNotInstanceOf(SimpleMeterRegistry.class);

        ResponseEntity<String> prometheus = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getHeaders().getContentType()).isNotNull();
        assertThat(prometheus.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(prometheus.getBody())
            .contains("jvm_memory_used_bytes")
            .contains("http_server_requests_seconds_count")
            .contains("http_server_requests_seconds_bucket")
            .contains("billing_checkout_total")
            .contains("billing_stripe_webhook_total")
            .contains("billing_stripe_webhook_duration_seconds_count")
            .contains("billing_stripe_signature_failure_total")
            .contains("billing_stripe_unknown_price_total")
            .contains("billing_quota_grant_total")
            .contains("quota_consume_total")
            .contains("quota_refund_total")
            .contains("le=\"0.01\"")
            .contains("le=\"10.0\"");
        assertThat(prometheus.getBody().lines()
            .filter(line -> line.startsWith("http_server_requests_seconds_count{"))
            .filter(line -> line.contains("uri=\"/v1/quota/balance\""))
            .toList())
            .anyMatch(line -> line.contains("status=\"200\""))
            .anyMatch(line -> line.contains("status=\"400\""))
            .anyMatch(line -> line.contains("status=\"500\""));
        assertHttpUriTemplate(prometheus.getBody(), "/v1/internal/verla/sessions/{sessionId}/context", "200");
        assertHttpUriTemplate(prometheus.getBody(), "/v1/internal/verla/conversations/{conversationId}/context", "200");
        assertHttpUriTemplate(prometheus.getBody(), "/v1/verla/conversations/{cid}/assignment/runtime-snapshot", "200");
        assertHttpUriTemplate(prometheus.getBody(), "/v1/verla/conversations/{cid}/ai-writing/runtime-snapshot", "400");
        assertHttpUriTemplate(prometheus.getBody(), "/v1/verla/conversations/{cid}/ai-writing/runtime-snapshot", "500");
        assertThat(bucketBounds(prometheus.getBody(), "http_server_requests_seconds_bucket"))
            .containsExactlyInAnyOrder(
                "0.01", "0.025", "0.05", "0.1", "0.25", "0.5",
                "1.0", "2.0", "5.0", "10.0", "+Inf"
            );
        assertThat(bucketBounds(prometheus.getBody(), "billing_checkout_duration_seconds_bucket"))
            .containsExactlyInAnyOrder(
                "0.01", "0.025", "0.05", "0.1", "0.25", "0.5",
                "1.0", "2.0", "5.0", "10.0", "+Inf"
            );
        assertThat(bucketBounds(prometheus.getBody(), "billing_stripe_webhook_duration_seconds_bucket"))
            .containsExactlyInAnyOrder(
                "0.01", "0.025", "0.05", "0.1", "0.25", "0.5",
                "1.0", "2.0", "5.0", "10.0", "+Inf"
            );
        assertThat(bucketBounds(prometheus.getBody(), "verla_upload_duration_seconds_bucket"))
            .containsExactlyInAnyOrder(
                "0.01", "0.025", "0.05", "0.1", "0.25", "0.5",
                "1.0", "2.0", "5.0", "10.0", "30.0", "+Inf"
            );

        assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> health = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).isNotNull();
        String healthBody = health.getBody().toLowerCase(Locale.ROOT);
        assertThat(healthBody)
            .doesNotContain("components")
            .doesNotContain("database")
            .doesNotContain("redis")
            .doesNotContain("rabbit");
    }

    @Test
    void monitoredBusinessEndpointsKeepTemplateMappings() {
        assertGetMapping(VerlaInternalController.class, "getSessionContext",
            "/v1/internal/verla/sessions/{sessionId}/context");
        assertGetMapping(VerlaInternalController.class, "getConversationContext",
            "/v1/internal/verla/conversations/{conversationId}/context");
        assertGetMapping(VerlaConversationController.class, "getAssignmentRuntimeSnapshot",
            "/v1/verla/conversations/{cid}/assignment/runtime-snapshot");
        assertGetMapping(VerlaConversationController.class, "getAiWritingRuntimeSnapshot",
            "/v1/verla/conversations/{cid}/ai-writing/runtime-snapshot");
    }

    private boolean containsPrometheusRegistry(MeterRegistry registry) {
        if (registry.getClass().getName().contains("PrometheusMeterRegistry")) {
            return true;
        }
        if (registry instanceof CompositeMeterRegistry composite) {
            return composite.getRegistries().stream().anyMatch(this::containsPrometheusRegistry);
        }
        return false;
    }

    private String describeRegistry(MeterRegistry registry) {
        if (registry instanceof CompositeMeterRegistry composite) {
            return registry.getClass().getName() + composite.getRegistries().stream()
                .map(candidate -> candidate.getClass().getName())
                .toList();
        }
        return registry.getClass().getName();
    }

    private Set<String> bucketBounds(String scrape, String metricName) {
        return Arrays.stream(scrape.split("\\R"))
            .filter(line -> line.startsWith(metricName + "{"))
            .map(LE_LABEL::matcher)
            .filter(Matcher::find)
            .map(matcher -> matcher.group(1))
            .collect(Collectors.toSet());
    }

    private void assertHttpUriTemplate(String scrape, String uri, String status) {
        assertThat(scrape.lines()
                .filter(line -> line.startsWith("http_server_requests_seconds_count{"))
                .filter(line -> line.contains("uri=\"" + uri + "\""))
                .toList())
                .anyMatch(line -> line.contains("status=\"" + status + "\""));
    }

    private void assertGetMapping(Class<?> controllerType, String methodName, String expectedPath) {
        RequestMapping base = controllerType.getAnnotation(RequestMapping.class);
        String basePath = base.value()[0];
        GetMapping mapping = Arrays.stream(controllerType.getDeclaredMethods())
            .filter(method -> method.getName().equals(methodName))
            .findFirst()
            .orElseThrow()
            .getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(basePath + mapping.value()[0]).isEqualTo(expectedPath);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(VerlaMetricsConfig.class)
    static class TestApplication {

        @RestController
        static class TestController {

            @GetMapping("/test/ping")
            String ping() {
                return "pong";
            }

            @GetMapping("/v1/quota/balance")
            String quotaBalance(
                    @RequestParam(defaultValue = "false") boolean fail,
                    @RequestParam(defaultValue = "0") int probe) {
                if (fail) {
                    throw new IllegalStateException("synthetic quota balance failure");
                }
                return "ok";
            }

            @GetMapping("/v1/internal/verla/sessions/{sessionId}/context")
            String sessionContext() {
                return "ok";
            }

            @GetMapping("/v1/internal/verla/conversations/{conversationId}/context")
            String conversationContext() {
                return "ok";
            }

            @GetMapping("/v1/verla/conversations/{cid}/assignment/runtime-snapshot")
            String assignmentRuntimeSnapshot() {
                return "ok";
            }

            @GetMapping("/v1/verla/conversations/{cid}/ai-writing/runtime-snapshot")
            String aiWritingRuntimeSnapshot(@RequestParam(defaultValue = "false") boolean fail) {
                if (fail) {
                    throw new IllegalStateException("synthetic ai-writing snapshot failure");
                }
                return "ok";
            }
        }
    }
}
