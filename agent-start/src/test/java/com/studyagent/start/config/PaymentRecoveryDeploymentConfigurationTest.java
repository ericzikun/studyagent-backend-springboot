package com.studyagent.start.config;

import com.studyagent.infra.service.billing.PaymentRecoveryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证实际 application.yml 与部署 env 的绑定，不启动数据库、支付或邮件服务。 */
class PaymentRecoveryDeploymentConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentRecoveryProperties.class)
    static class Binding {}

    private ApplicationContextRunner configured(Map<String, Object> env, Map<String, Object> lowerPriority) {
        return new ApplicationContextRunner().withUserConfiguration(Binding.class).withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            sources.addFirst(new MapPropertySource("lower-priority-config", lowerPriority));
            // Boot 3.1 selects its OS environment mapper by both source type and source name.
            sources.addFirst(new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
            try {
                for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")))
                    sources.addLast(source);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    private void assertClosed(PaymentRecoveryProperties config) {
        assertFalse(config.isAcceptNew());
        assertFalse(config.isAutomaticPayEnabled());
        assertFalse(config.isEmailEnabled());
        assertEquals(java.time.Instant.parse("2099-01-01T00:00:00Z"), config.getAcceptInvoicesCreatedAfter());
        assertTrue(config.getAllowedUserIds().isEmpty());
        assertTrue(config.getVerifiedCohorts().isEmpty());
    }

    @Test void applicationDefaultsKeepRecoveryClosed() {
        configured(Map.of(), Map.of()).run(context -> {
            assertNull(context.getStartupFailure());
            assertClosed(context.getBean(PaymentRecoveryProperties.class));
        });
    }

    @Test void deploymentEnvironmentClosesRecoveryAndClearsLowerPriorityLists() {
        configured(Map.of(
                "BILLING_PAYMENT_RECOVERY_ACCEPT_NEW", "false",
                "BILLING_PAYMENTRECOVERY_ACCEPTNEW", "false",
                "BILLING_PAYMENT_RECOVERY_AUTOMATIC_PAY_ENABLED", "false",
                "BILLING_PAYMENTRECOVERY_AUTOMATICPAYENABLED", "false",
                "BILLING_PAYMENTRECOVERY_EMAILENABLED", "false",
                "BILLING_PAYMENTRECOVERY_ALLOWEDUSERIDS", "",
                "BILLING_PAYMENTRECOVERY_VERIFIEDCOHORTS", ""),
                Map.of(
                        "billing.payment-recovery.accept-new", "true",
                        "billing.payment-recovery.automatic-pay-enabled", "true",
                        "billing.payment-recovery.email-enabled", "true",
                        "billing.payment-recovery.allowed-user-ids", "deployment-test-user",
                        "billing.payment-recovery.verified-cohorts", "formal:pro_monthly:month_1:NO_SCHEDULE:RENEW"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertClosed(context.getBean(PaymentRecoveryProperties.class));
                });
    }

    @Test void documentedEnvironmentVariablesBindThroughApplicationYaml() {
        String cohort = "formal:pro_monthly:month_1:NO_SCHEDULE:RENEW";
        configured(Map.ofEntries(
                Map.entry("BILLING_PAYMENT_RECOVERY_ACCEPT_NEW", "true"),
                Map.entry("BILLING_PAYMENT_RECOVERY_CREATED_AFTER", "2026-11-18T00:00:00Z"),
                Map.entry("BILLING_PAYMENT_RECOVERY_AUTOMATIC_PAY_ENABLED", "true"),
                Map.entry("BILLING_PAYMENT_RECOVERY_ALLOWED_USER_IDS", " user_a, ,user_b "),
                Map.entry("BILLING_PAYMENT_RECOVERY_VERIFIED_COHORTS", " " + cohort + ", "),
                Map.entry("BILLING_PAYMENT_RECOVERY_DISPATCH_TOLERANCE_SECONDS", "1200"),
                Map.entry("BILLING_PAYMENT_RECOVERY_BATCH_SIZE", "25"),
                Map.entry("BILLING_PAYMENT_RECOVERY_LEASE_SECONDS", "90"),
                Map.entry("BILLING_PAYMENT_RECOVERY_UNKNOWN_ESCALATION_HOURS", "12"),
                Map.entry("BILLING_PAYMENT_RECOVERY_PORTAL_MANAGEMENT_CONFIGURATION_ID", "bpc_test_manage"),
                Map.entry("BILLING_PAYMENT_RECOVERY_PORTAL_METHOD_ONLY_CONFIGURATION_ID", "bpc_test_method"),
                Map.entry("BILLING_PAYMENT_RECOVERY_EMAIL_ENABLED", "true"),
                Map.entry("BILLING_PAYMENT_RECOVERY_EMAIL_FROM", "Test <sender@example.test>"),
                Map.entry("BILLING_PAYMENT_RECOVERY_REVIEW_ASSIGNEE", "test-owner"),
                Map.entry("BILLING_PAYMENT_RECOVERY_RECONCILIATION_CRON", "0 5 * * * ?")), Map.of())
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    PaymentRecoveryProperties config = context.getBean(PaymentRecoveryProperties.class);
                    assertTrue(config.isAcceptNew());
                    assertTrue(config.isAutomaticPayEnabled());
                    assertTrue(config.isEmailEnabled());
                    assertEquals(java.time.Instant.parse("2026-11-18T00:00:00Z"), config.getAcceptInvoicesCreatedAfter());
                    assertEquals(java.util.Set.of("user_a", "user_b"), config.getAllowedUserIds());
                    assertEquals(java.util.Set.of(cohort), config.getVerifiedCohorts());
                    assertEquals(1200, config.getDispatchToleranceSeconds());
                    assertEquals(25, config.getBatchSize());
                    assertEquals(90, config.getLeaseSeconds());
                    assertEquals(12, config.getUnknownEscalationHours());
                    assertEquals("bpc_test_manage", config.getPortalManagementConfigurationId());
                    assertEquals("bpc_test_method", config.getPortalMethodOnlyConfigurationId());
                    assertEquals("Test <sender@example.test>", config.getEmailFrom());
                    assertEquals("test-owner", config.getReviewAssignee());
                    assertEquals("0 5 * * * ?", context.getEnvironment().getProperty("billing.payment-recovery.reconciliation-cron"));
                });
    }
}
