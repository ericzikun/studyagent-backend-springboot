package com.studyagent.start.billing;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.net.ApiResource;
import com.studyagent.api.config.WebConfig;
import com.studyagent.api.controller.BillingController;
import com.studyagent.api.controller.WebhookController;
import com.studyagent.api.interceptor.AuthInterceptor;
import com.studyagent.api.service.robot.RobotNotifyAsyncService;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.entity.SubscriptionRecoveryActionEntity;
import com.studyagent.infra.job.StripeWebhookRetryScheduler;
import com.studyagent.infra.job.SubscriptionPaymentRecoveryScheduler;
import com.studyagent.infra.mapper.*;
import com.studyagent.infra.service.QuotaRechargeService;
import com.studyagent.infra.service.billing.*;
import com.studyagent.service.domain.billing.*;
import com.studyagent.service.domain.payment.PaymentDomainService;
import com.studyagent.service.domain.user.ClerkClient;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real loopback HTTP, Stripe HMAC, MySQL inbox/CAS and naturally triggered cron.
 * Stripe, Clerk verification and external delivery adapters are test doubles;
 * this does not certify live Clerk/Stripe, financial retries or production mail.
 * Opt-in only: an initially empty, local payment_recovery_test_* schema is mandatory.
 */
@EnabledIfEnvironmentVariable(named = "PAYMENT_RECOVERY_HTTP_IT", matches = "true")
@SpringBootTest(classes = SubscriptionPaymentRecoveryHttpSchedulerMysqlIT.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        args = "--spring.config.location=optional:classpath:/recovery-http-scheduler-isolated.properties",
        properties = {
                "server.address=127.0.0.1", "spring.profiles.active=recovery-http-it",
                "spring.main.banner-mode=off", "logging.level.root=ERROR",
                "logging.level.com.studyagent.infra.job=WARN",
                "stripe.webhook-secret=whsec_recovery_http_fixture", "stripe.allow-unsigned-webhooks=false",
                "auth.dev-bypass.enabled=false", "billing.webhook-retry.max-attempts=3",
                "billing.webhook-retry.cron=*/1 * * * * ?",
                "billing.payment-recovery.reconciliation-cron=*/1 * * * * ?",
                "billing.payment-recovery.review-cron=-"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SubscriptionPaymentRecoveryHttpSchedulerMysqlIT {
    private static final String OWNER = "http_owner";
    private static final String SECRET = "whsec_recovery_http_fixture";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SubscriptionPaymentRecoveryService webhookRecovery;
    @Autowired SubscriptionRecoveryStripeGateway stripe;
    @Autowired BillingDomainService billing;
    @Autowired ClerkClient clerk;
    @Autowired UserSubscriptionMapper legacyUsers;
    @Autowired AnalyticsService analytics;
    @Autowired ThreadPoolTaskScheduler taskScheduler;
    @Autowired PaymentRecoveryProperties config;

    @BeforeEach void fixtures() {
        until(() -> taskScheduler.getActiveCount() == 0);
        reset(webhookRecovery, stripe, billing, clerk, legacyUsers, analytics);
        when(webhookRecovery.tryProcessEvent(any())).thenReturn(true);
        ClerkClient.UserInfo owner = new ClerkClient.UserInfo(); owner.clerkUserId = OWNER;
        when(clerk.verifyToken("valid-owner-token")).thenReturn(owner);
        when(clerk.verifyToken("invalid-token")).thenThrow(new IllegalArgumentException("invalid token"));
        var invoice = ApiResource.GSON.fromJson("""
                {"id":"in_owned","customer":"cus_owned","subscription":"sub_owned","status":"open",
                 "amount_paid":0,"amount_due":7999,"amount_remaining":7999,"currency":"usd","auto_advance":false}
                """, Invoice.class);
        var sub = ApiResource.GSON.fromJson("""
                {"id":"sub_owned","customer":"cus_owned","status":"past_due","latest_invoice":"in_owned"}
                """, Subscription.class);
        when(stripe.readInvoice("in_owned")).thenAnswer(call ->
                new SubscriptionRecoveryStripeGateway.Snapshot(invoice, sub, null, List.of(), null, null, Instant.now()));
        when(stripe.listInvoices("sub_owned")).thenReturn(List.of(invoice));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            for (String table : List.of("subscription_payment_recovery_actions", "subscription_payment_recovery_replacements",
                    "subscription_payment_recoveries", "user_subscriptions", "stripe_webhook_events"))
                db.update("DELETE FROM " + table);
            LocalDateTime now = DateTimeFormats.now();
            db.update("INSERT INTO user_subscriptions(id,clerk_user_id,tier,plan_code,status,stripe_subscription_id,payment_recovery_id) VALUES(1,?,'pro','pro_monthly','past_due','sub_owned',1)", OWNER);
            db.update("""
                    INSERT INTO subscription_payment_recoveries(id,clerk_user_id,stripe_livemode,stripe_customer_id,
                     stripe_subscription_id,stripe_invoice_id,stripe_invoice_status,plan_code,display_tier,billing_interval,
                     contract_snapshot,currency,amount_due_minor,amount_remaining_minor,first_failed_at,deadline_at,status,
                     protection_state,next_reconcile_at) VALUES(1,?,0,'cus_owned','sub_owned','in_owned','open','pro_monthly',
                     'pro','month','{}','usd',7999,7999,?,?,'OPEN_MANUAL','ACTIVE',?)
                    """, OWNER, now.minusMinutes(1), now.plusDays(30), now.plusDays(1));
        });
    }

    @Test void missingSignatureCannotReachInbox() throws Exception {
        int status = post("/v1/webhook/stripe", event("evt_missing"), Map.of()).statusCode();
        assertTrue(status >= 400 && status < 500);
        assertEquals(0L, countInbox()); verifyNoInteractions(webhookRecovery);
    }

    @Test void invalidAndTamperedSignaturesCannotReachInbox() throws Exception {
        String payload = event("evt_invalid");
        assertEquals(401, post("/v1/webhook/stripe", payload, Map.of("stripe-signature", "t=" + Instant.now().getEpochSecond() + ",v1=bad")).statusCode());
        assertEquals(401, post("/v1/webhook/stripe", payload + " ", Map.of("stripe-signature", sign(payload, Instant.now().getEpochSecond()))).statusCode());
        assertEquals(0L, countInbox()); verifyNoInteractions(webhookRecovery);
    }

    @Test void expiredSignatureCannotReachInbox() throws Exception {
        String payload = event("evt_expired");
        assertEquals(401, post("/v1/webhook/stripe", payload, Map.of("stripe-signature", sign(payload, Instant.now().minusSeconds(600).getEpochSecond()))).statusCode());
        assertEquals(0L, countInbox()); verifyNoInteractions(webhookRecovery);
    }

    @Test void signedDuplicateHasOneDurableClaimAndNeverFallsIntoLegacy() throws Exception {
        String payload = event("evt_duplicate");
        assertEquals(200, webhook(payload).statusCode());
        assertEquals(200, webhook(payload).statusCode());
        assertEquals("succeeded", inboxStatus("evt_duplicate"));
        assertEquals(1, inboxAttempts("evt_duplicate")); assertEquals(1L, countInbox());
        verify(webhookRecovery, times(1)).tryProcessEvent(any()); verifyNoInteractions(legacyUsers);
    }

    @Test void concurrentDuplicateCannotStealAnActiveClaim() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); return true; })
                .when(webhookRecovery).tryProcessEvent(any());
        String payload = event("evt_concurrent");
        var first = http.sendAsync(request("/v1/webhook/stripe", payload,
                Map.of("stripe-signature", sign(payload, Instant.now().getEpochSecond()))), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(200, webhook(payload).statusCode());
            assertEquals("processing", inboxStatus("evt_concurrent"));
            assertEquals(1, inboxAttempts("evt_concurrent"));
        } finally { release.countDown(); }
        assertEquals(200, first.get(10, TimeUnit.SECONDS).statusCode());
        assertEquals("succeeded", inboxStatus("evt_concurrent"));
        verify(webhookRecovery, times(1)).tryProcessEvent(any()); verifyNoInteractions(legacyUsers);
    }

    @Test void readFailureReturns500ThenNaturalCronRetriesTheStoredPayload() throws Exception {
        AtomicBoolean readable = new AtomicBoolean();
        doAnswer(call -> { if (!readable.get()) throw new IllegalStateException("RECOVERY_ADMISSION_INCOMPLETE_EVIDENCE:in_http"); return true; })
                .when(webhookRecovery).tryProcessEvent(any());
        assertEquals(500, webhook(event("evt_read_retry")).statusCode());
        assertEquals("failed", inboxStatus("evt_read_retry"));
        assertTrue(db.queryForObject("SELECT payload_json FROM stripe_webhook_events WHERE event_id='evt_read_retry'", String.class).contains("in_http"));
        readable.set(true);
        db.update("UPDATE stripe_webhook_events SET next_retry_at=? WHERE event_id='evt_read_retry'", DateTimeFormats.now().minusSeconds(1));
        until(() -> "succeeded".equals(inboxStatus("evt_read_retry")));
        assertEquals(2, inboxAttempts("evt_read_retry")); verifyNoInteractions(legacyUsers);
    }

    @Test void unknownScheduleReachesDeadLetterWithoutLegacyFallback() throws Exception {
        doThrow(new IllegalStateException("RECOVERY_ADMISSION_UNKNOWN_SCHEDULE:sub_http"))
                .when(webhookRecovery).tryProcessEvent(any());
        assertEquals(500, webhook(event("evt_unknown_schedule")).statusCode());
        for (int attempt = 2; attempt <= 3; attempt++) {
            int expected = attempt;
            db.update("UPDATE stripe_webhook_events SET next_retry_at=? WHERE event_id='evt_unknown_schedule'", DateTimeFormats.now().minusSeconds(1));
            until(() -> inboxAttempts("evt_unknown_schedule") >= expected
                    && !"processing".equals(inboxStatus("evt_unknown_schedule")));
        }
        assertEquals("dead_letter", inboxStatus("evt_unknown_schedule"));
        assertNull(db.queryForObject("SELECT next_retry_at FROM stripe_webhook_events WHERE event_id='evt_unknown_schedule'", Object.class));
        int calls = mockingDetails(webhookRecovery).getInvocations().size(); awaitMoreTicks();
        assertEquals(calls, mockingDetails(webhookRecovery).getInvocations().size());
        verifyNoInteractions(legacyUsers);
    }

    @Test void protectedRecoveryRejectsMissingAndInvalidBearerWithoutDevBypass() throws Exception {
        assertEquals(401, post("/v1/billing/recoveries/1/reconcile", "{}", Map.of("X-Dev-User", OWNER)).statusCode());
        assertEquals(401, post("/v1/billing/recoveries/1/reconcile", "{}", Map.of("Authorization", "Bearer invalid-token")).statusCode());
        verifyNoInteractions(billing);
    }

    @Test void authenticatedIdentityOverridesSpoofedDevUserAndIdRemainsServerArgument() throws Exception {
        when(billing.reconcilePaymentRecovery(OWNER, "1")).thenReturn(summary());
        var response = post("/v1/billing/recoveries/1/reconcile", "{}",
                Map.of("Authorization", "Bearer valid-owner-token", "X-Dev-User", "intruder"));
        assertEquals(200, response.statusCode());
        var body = new ObjectMapper().readTree(response.body());
        assertEquals(0, body.path("meta").path("statusCode").asInt());
        assertEquals("1", body.path("data").path("recoveryId").asText());
        verify(billing).reconcilePaymentRecovery(OWNER, "1");
        verify(billing, never()).reconcilePaymentRecovery(eq("intruder"), anyString());
    }

    @Test void ownershipRejectionKeepsTheDomainErrorAndDoesNotCreateAPaymentLink() throws Exception {
        when(billing.reconcilePaymentRecovery(OWNER, "999")).thenThrow(new BillingDomainException("BILLING_RECORD_NOT_FOUND", "not owned"));
        var response = post("/v1/billing/recoveries/999/reconcile", "{}", Map.of("Authorization", "Bearer valid-owner-token"));
        assertEquals(1026, new ObjectMapper().readTree(response.body()).path("meta").path("statusCode").asInt());
        verify(billing, never()).createRecoveryHostedInvoice(anyString(), anyString());
    }

    @Test void actualCronClaimsAndPersistsAnOwnedActionBeforeCallingTheAdapterOnce() {
        assertFalse(config.isAcceptNew()); assertFalse(config.isAutomaticPayEnabled()); assertFalse(config.isEmailEnabled());
        when(stripe.execute(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            SubscriptionRecoveryActionEntity sent = call.getArgument(0);
            var row = db.queryForMap("SELECT status,transport_attempts,lease_token,payload_json,provider_idempotency_key FROM subscription_payment_recovery_actions WHERE id=?", sent.getId());
            assertEquals("DISPATCHING", row.get("status")); assertEquals(1, ((Number) row.get("transport_attempts")).intValue());
            assertEquals(sent.getLeaseToken(), row.get("lease_token"));
            assertEquals("http-portal-idempotency", row.get("provider_idempotency_key"));
            assertTrue(row.get("payload_json").toString().contains("frozen"));
            return new SubscriptionRecoveryStripeGateway.WriteResult("SUCCEEDED", "req_local_http", null, null);
        });
        action("CREATE_PORTAL", "READY", null);
        until(() -> "SUCCEEDED".equals(actionStatus())); awaitMoreTicks();
        assertEquals(1, db.queryForObject("SELECT transport_attempts FROM subscription_payment_recovery_actions WHERE id=1", Integer.class));
        verify(stripe, times(1)).execute(any()); verifyNoInteractions(webhookRecovery);
    }

    @Test void unknownApplicationDeliveryIsNotResentBySubsequentNaturalScans() {
        action("CAPTURE_FAILURE", "READY", null);
        until(() -> "UNKNOWN".equals(actionStatus())); awaitMoreTicks();
        assertEquals("UNKNOWN", actionStatus());
        assertEquals(1, db.queryForObject("SELECT transport_attempts FROM subscription_payment_recovery_actions WHERE id=1", Integer.class));
        verify(analytics, times(1)).capture(eq(OWNER), anyString(), anyMap()); verify(stripe, never()).execute(any());
    }

    @Test void expiredApplicationLeaseBeyondReplayWindowIsReviewedWithoutResending() {
        action("CAPTURE_FAILURE", "DISPATCHING", DateTimeFormats.now().minusDays(3));
        until(() -> "REVIEW".equals(actionStatus())); awaitMoreTicks();
        verifyNoInteractions(analytics); verify(stripe, never()).execute(any());
    }

    private void action(String kind, String status, LocalDateTime dispatched) {
        var now = DateTimeFormats.now();
        db.update("""
                INSERT INTO subscription_payment_recovery_actions(id,recovery_id,action_type,action_key,target_id,scheduled_at,
                 next_run_at,status,payload_json,provider_idempotency_key,first_dispatched_at,transport_attempts,lease_token,lease_until)
                VALUES(1,1,?,'http:owned-action','sub_owned',?,?,?, ?, 'http-portal-idempotency',?,?,?,?)
                """, kind, now.minusSeconds(1), now.minusSeconds(1), status, "{\"fixture\":\"frozen\"}", dispatched,
                dispatched == null ? 0 : 1, dispatched == null ? null : "expired-lease", dispatched == null ? null : now.minusSeconds(1));
    }
    private String actionStatus() { return db.queryForObject("SELECT status FROM subscription_payment_recovery_actions WHERE id=1", String.class); }
    private long countInbox() { return db.queryForObject("SELECT COUNT(*) FROM stripe_webhook_events", Long.class); }
    private String inboxStatus(String id) { return db.queryForObject("SELECT status FROM stripe_webhook_events WHERE event_id=?", String.class, id); }
    private int inboxAttempts(String id) { return db.queryForObject("SELECT attempt_count FROM stripe_webhook_events WHERE event_id=?", Integer.class, id); }
    private void awaitMoreTicks() { long count = taskScheduler.getScheduledThreadPoolExecutor().getCompletedTaskCount(); until(() -> taskScheduler.getScheduledThreadPoolExecutor().getCompletedTaskCount() >= count + 4); }
    private void until(BooleanSupplier ready) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Condition not reached by real HTTP/scheduler before deadline; actions="
                    + db.queryForList("SELECT action_type,status,transport_attempts,first_dispatched_at,lease_until,result_json,last_error FROM subscription_payment_recovery_actions"));
            try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }
    private HttpRequest request(String path, String payload, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload));
        headers.forEach(builder::header); return builder.build();
    }
    private HttpResponse<String> post(String path, String payload, Map<String, String> headers) throws Exception {
        return http.send(request(path, payload, headers), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> webhook(String payload) throws Exception { return post("/v1/webhook/stripe", payload, Map.of("stripe-signature", sign(payload, Instant.now().getEpochSecond()))); }
    private static String sign(String payload, long time) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "t=" + time + ",v1=" + HexFormat.of().formatHex(mac.doFinal((time + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }
    private static String event(String id) {
        return "{\"id\":\"" + id + "\",\"object\":\"event\",\"api_version\":\"2023-10-16\",\"created\":" + Instant.now().getEpochSecond()
                + ",\"livemode\":false,\"type\":\"invoice.payment_failed\",\"data\":{\"object\":{\"id\":\"in_http\",\"object\":\"invoice\",\"subscription\":\"sub_http\",\"customer\":\"cus_http\"}}}";
    }
    private static PaymentRecoverySummary summary() { return new PaymentRecoverySummary("1", "OPEN_MANUAL", true, "pro", "pro_monthly", 7999L, 0L, 7999L, "usd", null, null, null, List.of(), "NONE", "NONE", null); }

    // Keep this explicit fixture out of unrelated application-context scans.
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Profile("recovery-http-it")
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, RabbitAutoConfiguration.class, MybatisPlusAutoConfiguration.class})
    @EnableScheduling
    @Import({WebhookController.class, BillingController.class, AuthInterceptor.class, WebConfig.class, StripeWebhookRetryScheduler.class})
    static class Config {
        @Bean DataSource dataSource() throws Exception {
            String url = System.getenv("PAYMENT_RECOVERY_TEST_JDBC_URL");
            if (url == null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/payment_recovery_test_[a-z0-9_]+\\?.*serverTimezone=Asia/Shanghai.*"))
                throw new IllegalArgumentException("Only an explicitly supplied isolated local test schema is allowed");
            HikariConfig pool = new HikariConfig(); pool.setJdbcUrl(url); pool.setUsername(System.getenv("PAYMENT_RECOVERY_TEST_DB_USER"));
            pool.setPassword(System.getenv("PAYMENT_RECOVERY_TEST_DB_PASSWORD")); pool.setMaximumPoolSize(8);
            var ds = new HikariDataSource(pool);
            try (Connection c = ds.getConnection(); var q = c.createStatement()) {
                try (var tables = q.executeQuery("SHOW TABLES")) { if (tables.next()) throw new IllegalArgumentException("Test schema must initially be empty"); }
                String initial = Files.readString(Path.of("../sql/057_user_subscriptions_and_webhook_events.sql"));
                for (String table : List.of("user_subscriptions", "stripe_webhook_events")) executeStatement(q, initial, "CREATE TABLE IF NOT EXISTS " + table);
                for (String file : List.of("066_manual_subscription_upgrade_checkout.sql", "069_commercialization_integrity.sql", "073_user_subscriptions_intro_trial_fields.sql"))
                    executeStatement(q, Files.readString(Path.of("../sql/" + file)), "ALTER TABLE user_subscriptions");
                executeStatement(q, Files.readString(Path.of("../sql/069_commercialization_integrity.sql")), "ALTER TABLE stripe_webhook_events");
                q.execute("ALTER TABLE user_subscriptions ADD COLUMN pricing_zone VARCHAR(8), ADD COLUMN price_version VARCHAR(32), ADD COLUMN currency CHAR(3), ADD COLUMN pricing_country CHAR(2)");
                for(String migration:List.of("097_subscription_payment_recovery.sql","098_subscription_period_time_zones.sql"))
                    for (String statement : Files.readString(Path.of("../sql/"+migration)).split(";"))
                        if (!statement.isBlank()) q.execute(statement.replace("USE studyagent", "USE " + url.split("/")[3].split("\\?")[0]));
            } catch (Exception e) { ds.close(); throw e; }
            return ds;
        }
        private static void executeStatement(java.sql.Statement q, String sql, String prefix) throws Exception {
            int start = sql.indexOf(prefix); if (start < 0) throw new IllegalArgumentException(prefix);
            q.execute(sql.substring(start, sql.indexOf(";\n", start)));
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds);
            // Use the production optimistic-lock/pagination chain: updateById on
            // @Version entities requires the same plugin used by the application.
            factory.setPlugins(new com.studyagent.infra.config.MyBatisConfig().mybatisPlusInterceptor());
            var result = factory.getObject();
            for (Class<?> mapper : List.of(StripeWebhookEventMapper.class, UserSubscriptionMapper.class, SubscriptionPaymentRecoveryMapper.class, SubscriptionRecoveryActionMapper.class))
                result.getConfiguration().addMapper(mapper);
            return result;
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean StripeWebhookEventMapper inbox(SqlSessionTemplate sql) { return sql.getMapper(StripeWebhookEventMapper.class); }
        @Bean PlatformTransactionManager transactions(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean PaymentRecoveryProperties properties() { return new PaymentRecoveryProperties(); }
        // Adapter doubles are registered as singletons so Spring does not inject
        // unrelated production fields into the mocked concrete service classes.
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor adapters() {
            return beans -> {
                beans.registerSingleton("webhookRecovery", mock(SubscriptionPaymentRecoveryService.class));
                beans.registerSingleton("stripe", mock(SubscriptionRecoveryStripeGateway.class));
                beans.registerSingleton("legacyUsers", mock(UserSubscriptionMapper.class));
                beans.registerSingleton("analytics", mock(AnalyticsService.class));
                beans.registerSingleton("billing", mock(BillingDomainService.class));
                beans.registerSingleton("payment", mock(PaymentDomainService.class));
                beans.registerSingleton("clerk", mock(ClerkClient.class));
                beans.registerSingleton("recharge", mock(QuotaRechargeService.class));
                beans.registerSingleton("robot", mock(RobotNotifyAsyncService.class));
            };
        }
        @Bean ThreadPoolTaskScheduler taskScheduler() { var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(3); scheduler.setThreadNamePrefix("recovery-acceptance-"); scheduler.setWaitForTasksToCompleteOnShutdown(true); scheduler.setAwaitTerminationSeconds(10); return scheduler; }
        @Bean StripeBillingWebhookService webhookService(StripeWebhookEventMapper inbox, UserSubscriptionMapper legacyUsers,
                AnalyticsService analytics, ObjectProvider<BillingQuotaGateway> quota, ObjectProvider<BillingRobotNotifyGateway> robot,
                ObjectProvider<BillingDomainService> billing, PlatformTransactionManager tx) {
            return new StripeBillingWebhookService(inbox, legacyUsers, mock(SubscriptionPlanMapper.class), mock(AddonPackageDefMapper.class),
                    mock(RechargeOrderMapper.class), analytics, quota, robot, billing, tx, mock(BillingPricingService.class), mock(ZonePricingSelectionService.class));
        }
        @Bean SubscriptionPaymentRecoveryScheduler recoveryScheduler(SqlSessionTemplate sql, SubscriptionRecoveryStripeGateway stripe,
                PaymentRecoveryProperties config, PlatformTransactionManager tx, AnalyticsService analytics) {
            var roots = sql.getMapper(SubscriptionPaymentRecoveryMapper.class); var actions = sql.getMapper(SubscriptionRecoveryActionMapper.class);
            var service = new SubscriptionPaymentRecoveryService(sql.getMapper(UserSubscriptionMapper.class), roots, actions,
                    mock(SubscriptionRecoveryReplacementMapper.class), mock(SubscriptionPlanMapper.class), mock(RechargeOrderMapper.class),
                    stripe, config, mock(PaymentRecoveryAccessService.class), tx);
            ReflectionTestUtils.setField(service, "analytics", analytics);
            return new SubscriptionPaymentRecoveryScheduler(roots, actions, service, config);
        }
    }
}
