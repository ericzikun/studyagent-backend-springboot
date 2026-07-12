package com.studyagent.infra.service;

import com.studyagent.common.analytics.AnalyticsEvents;
import com.studyagent.common.analytics.AnalyticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class QuotaGrantAnalyticsPublisherTest {

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void publishAfterCommit_defersCaptureUntilTransactionCommits() {
        AnalyticsService analyticsService = mock(AnalyticsService.class);
        QuotaGrantAnalyticsPublisher publisher = new QuotaGrantAnalyticsPublisher(analyticsService);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(event());

        verify(analyticsService, never()).capture(eq("user_1"), eq(AnalyticsEvents.QUOTA_GRANT_SUCCEEDED), org.mockito.ArgumentMatchers.any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
        synchronization.afterCommit();

        ArgumentCaptor<Map<String, Object>> properties = ArgumentCaptor.forClass(Map.class);
        verify(analyticsService).capture(eq("user_1"), eq(AnalyticsEvents.QUOTA_GRANT_SUCCEEDED), properties.capture());
        assertThat(properties.getValue())
                .containsEntry("grant_type", "subscription_initial")
                .containsEntry("feature_code", "ai_detection")
                .containsEntry("quota_amount", 10_000L)
                .containsEntry("quota_unit", "words")
                .containsEntry("plan_code", "basic_monthly")
                .containsEntry("source_type", "invoice")
                .containsEntry("source_id", "in_1")
                .containsEntry("idempotency_key", "invoice:in_1:plan:ai_detection")
                .containsEntry("quota_period_start", "2026-07-01T00:00:00")
                .containsEntry("quota_period_end", "2026-08-01T00:00:00")
                .doesNotContainKey("addon_code");
    }

    @Test
    void publishAfterCommit_capturesImmediatelyWithoutTransaction() {
        AnalyticsService analyticsService = mock(AnalyticsService.class);
        QuotaGrantAnalyticsPublisher publisher = new QuotaGrantAnalyticsPublisher(analyticsService);

        publisher.publishAfterCommit(event());

        verify(analyticsService).capture(eq("user_1"), eq(AnalyticsEvents.QUOTA_GRANT_SUCCEEDED), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publishAfterCommit_marksUnknownFeatureUnitAsUnknown() {
        AnalyticsService analyticsService = mock(AnalyticsService.class);
        QuotaGrantAnalyticsPublisher publisher = new QuotaGrantAnalyticsPublisher(analyticsService);
        QuotaGrantAnalyticsEvent event = new QuotaGrantAnalyticsEvent(
                "user_1",
                "addon",
                "future_time_feature",
                2L,
                null,
                "future_addon",
                "checkout",
                "cs_1",
                "checkout:cs_1:addon",
                null,
                null);

        publisher.publishAfterCommit(event);

        ArgumentCaptor<Map<String, Object>> properties = ArgumentCaptor.forClass(Map.class);
        verify(analyticsService).capture(eq("user_1"), eq(AnalyticsEvents.QUOTA_GRANT_SUCCEEDED), properties.capture());
        assertThat(properties.getValue()).containsEntry("quota_unit", "unknown");
    }

    private QuotaGrantAnalyticsEvent event() {
        return new QuotaGrantAnalyticsEvent(
                "user_1",
                "subscription_initial",
                "ai_detection",
                10_000L,
                "basic_monthly",
                null,
                "invoice",
                "in_1",
                "invoice:in_1:plan:ai_detection",
                LocalDateTime.parse("2026-07-01T00:00:00"),
                LocalDateTime.parse("2026-08-01T00:00:00"));
    }
}
