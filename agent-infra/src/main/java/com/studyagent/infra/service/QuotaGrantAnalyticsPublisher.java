package com.studyagent.infra.service;

import com.studyagent.common.analytics.AnalyticsEvents;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.common.quota.FeatureCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class QuotaGrantAnalyticsPublisher {
    private static final DateTimeFormatter EVENT_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Set<String> WORD_BASED_FEATURE_CODES = Set.of(
            FeatureCode.AI_DETECTION.getCode(),
            FeatureCode.HUMANIZER.getCode());

    private final AnalyticsService analyticsService;

    public void publishAfterCommit(QuotaGrantAnalyticsEvent event) {
        if (event == null || event.quotaAmount() <= 0) {
            return;
        }

        Runnable capture = () -> capture(event);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    capture.run();
                }
            });
            return;
        }

        capture.run();
    }

    private void capture(QuotaGrantAnalyticsEvent event) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("grant_type", event.grantType());
        properties.put("feature_code", event.featureCode());
        properties.put("quota_amount", event.quotaAmount());
        properties.put("quota_unit", quotaUnit(event.featureCode()));
        putIfPresent(properties, "plan_code", event.planCode());
        putIfPresent(properties, "addon_code", event.addonCode());
        putIfPresent(properties, "source_type", event.sourceType());
        putIfPresent(properties, "source_id", event.sourceId());
        putIfPresent(properties, "idempotency_key", event.idempotencyKey());
        if (event.quotaPeriodStart() != null) {
            properties.put("quota_period_start", event.quotaPeriodStart().format(EVENT_TIME_FORMATTER));
        }
        if (event.quotaPeriodEnd() != null) {
            properties.put("quota_period_end", event.quotaPeriodEnd().format(EVENT_TIME_FORMATTER));
        }
        analyticsService.capture(event.clerkUserId(), AnalyticsEvents.QUOTA_GRANT_SUCCEEDED, properties);
    }

    private String quotaUnit(String featureCode) {
        if (FeatureCode.TASK_CREATE.getCode().equals(featureCode)) {
            return "time";
        }
        if (WORD_BASED_FEATURE_CODES.contains(featureCode)) {
            return "words";
        }
        return "unknown";
    }

    private void putIfPresent(Map<String, Object> properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.put(key, value);
        }
    }
}
