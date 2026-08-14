package com.studyagent.infra.client.clerk;

import com.studyagent.infra.metrics.ExternalDependencyMetrics;
import com.studyagent.service.domain.user.UserRepository;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClerkClientMetricsTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void remote_api_records_success_http_error_and_slow_duration() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/users/user_ok", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"id\":\"user_ok\",\"email_addresses\":[{\"email_address\":\"a@example.com\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/users/user_error", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.createContext("/users/user_slow", exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(1_200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            byte[] body = "{\"id\":\"user_slow\",\"email_addresses\":[{\"email_address\":\"slow@example.com\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ClerkClientImpl client = new ClerkClientImpl(WebClient.builder().build(),
                (UserRepository) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {UserRepository.class}, (proxy, method, args) -> null),
                new ExternalDependencyMetrics(meterRegistry));
        ReflectionTestUtils.setField(client, "clerkSecretKey", "test-key");
        ReflectionTestUtils.setField(client, "clerkApiUrl", "http://127.0.0.1:" + server.getAddress().getPort());

        assertEquals("a@example.com", client.getUserEmail("user_ok"));
        assertEquals(null, client.getUserEmail("user_error"));
        assertEquals("slow@example.com", client.getUserEmail("user_slow"));
        assertEquals(3, calls.get());
        assertCount(meterRegistry, "success", "none", 2.0);
        assertCount(meterRegistry, "error", "clerk_429", 1.0);
        assertTrue(meterRegistry.get("studyagent.external.request.duration")
                .tags("dependency", "clerk", "operation", "remote_api", "result", "success", "error_type", "none")
                .timer().totalTime(TimeUnit.MILLISECONDS) >= 1_000.0);
    }

    @Test
    void local_jwt_verification_failure_does_not_create_external_dependency_metric() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ClerkClientImpl client = new ClerkClientImpl(WebClient.builder().build(),
                (UserRepository) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {UserRepository.class}, (proxy, method, args) -> null),
                new ExternalDependencyMetrics(meterRegistry));

        assertThrows(IllegalStateException.class, () -> client.verifyToken("not-a-jwt"));
        assertEquals(0, meterRegistry.find("studyagent.external.requests").counters().size());
    }

    private void assertCount(SimpleMeterRegistry meterRegistry, String result, String errorType, double expected) {
        assertEquals(expected, meterRegistry.get("studyagent.external.requests")
                .tags("dependency", "clerk", "operation", "remote_api", "result", result,
                        "error_type", errorType)
                .counter().count());
    }
}
