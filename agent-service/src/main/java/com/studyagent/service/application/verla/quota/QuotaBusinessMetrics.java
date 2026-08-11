package com.studyagent.service.application.verla.quota;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class QuotaBusinessMetrics {
    private static final Set<String> GRANT_TYPES = Set.of(
            "initial", "renewal", "upgrade", "addon", "manual_upgrade");
    private static final Set<String> PURCHASE_TYPES = Set.of(
            "subscription_initial", "subscription_renewal", "subscription_upgrade", "addon");

    private final MeterRegistry meterRegistry;

    public void recordConsume(String featureCode, Result result) {
        Counter counter = Counter.builder("quota.consume")
                .tags("feature", feature(featureCode), "result", result.tag())
                .register(meterRegistry);
        increment(counter, result == Result.SUCCESS);
    }

    public void recordRefund(String featureCode, String rawReason, Result result) {
        Counter counter = Counter.builder("quota.refund")
                .tags("feature", feature(featureCode),
                        "trigger", refundTrigger(rawReason), "result", result.tag())
                .register(meterRegistry);
        increment(counter, result == Result.SUCCESS);
    }

    public void recordGrant(
            String grantType,
            String featureCode,
            String purchaseType,
            String productCode,
            Result result) {
        Counter counter = Counter.builder("billing.quota.grant")
                .tags(
                        "grant_type", grantType(grantType),
                        "feature", feature(featureCode),
                        "purchase_type", fixedValue(purchaseType, PURCHASE_TYPES),
                        "product_code", productCode == null || productCode.isBlank() ? "unknown" : productCode,
                        "result", result.tag())
                .register(meterRegistry);
        increment(counter, result == Result.SUCCESS);
    }

    private void increment(Counter counter, boolean afterCommit) {
        if (afterCommit && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    counter.increment();
                }
            });
            return;
        }
        counter.increment();
    }

    private String feature(String featureCode) {
        if ("ai_detection".equals(featureCode)) {
            return "detection";
        }
        if ("humanizer".equals(featureCode)) {
            return "humanizer";
        }
        return "assignment";
    }

    private String refundTrigger(String reason) {
        if (reason == null) {
            return "other";
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        if (normalized.contains("assignment_setup")) {
            return "assignment_setup_failed";
        }
        if (normalized.contains("init")) {
            return "init_failed";
        }
        if (normalized.contains("cancel")) {
            return "agent_cancelled";
        }
        if (normalized.contains("fail")) {
            return "agent_failed";
        }
        return "other";
    }

    private String fixedValue(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : "other";
    }

    private String grantType(String value) {
        String normalized = switch (value == null ? "" : value) {
            case "subscription_initial" -> "initial";
            case "subscription_renewal" -> "renewal";
            case "subscription_upgrade" -> "upgrade";
            default -> value;
        };
        return fixedValue(normalized, GRANT_TYPES);
    }

    public enum Result {
        SUCCESS,
        INSUFFICIENT,
        ERROR,
        SKIPPED;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
