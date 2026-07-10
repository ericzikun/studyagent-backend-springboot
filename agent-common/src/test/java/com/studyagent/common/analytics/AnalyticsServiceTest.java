package com.studyagent.common.analytics;

import com.posthog.server.PostHogCaptureOptions;
import com.posthog.server.PostHogInterface;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

class AnalyticsServiceTest {

    @Test
    void capture_adds_environment_and_app_version_defaults() {
        AnalyticsService analyticsService = new AnalyticsService();
        PostHogInterface postHog = mock(PostHogInterface.class);
        ReflectionTestUtils.setField(analyticsService, "enabled", true);
        ReflectionTestUtils.setField(analyticsService, "environment", "staging");
        ReflectionTestUtils.setField(analyticsService, "appVersion", "v2");
        ReflectionTestUtils.setField(analyticsService, "postHog", postHog);

        analyticsService.capture("user_1", "assignment:generation:started", Map.of(
                "conversation_id", 418L,
                "task_type", "assignment"));

        ArgumentCaptor<PostHogCaptureOptions> optionsCaptor = ArgumentCaptor.forClass(PostHogCaptureOptions.class);
        verify(postHog).capture(org.mockito.ArgumentMatchers.eq("user_1"),
                org.mockito.ArgumentMatchers.eq("assignment:generation:started"),
                optionsCaptor.capture());

        assertThat(optionsCaptor.getValue().getProperties())
                .containsEntry("conversation_id", 418L)
                .containsEntry("task_type", "assignment")
                .containsEntry("event_source", "backend")
                .containsEntry("event_version", "v2")
                .containsEntry("environment", "staging")
                .containsEntry("app_version", "v2");
    }

    @Test
    void capture_skips_blank_distinct_id() {
        AnalyticsService analyticsService = new AnalyticsService();
        PostHogInterface postHog = mock(PostHogInterface.class);
        ReflectionTestUtils.setField(analyticsService, "enabled", true);
        ReflectionTestUtils.setField(analyticsService, "postHog", postHog);

        analyticsService.capture(" ", "billing:payment:succeeded", Map.of());

        verifyNoInteractions(postHog);
    }

    @Test
    void destroy_flushes_before_closing_client() {
        AnalyticsService analyticsService = new AnalyticsService();
        PostHogInterface postHog = mock(PostHogInterface.class);
        ReflectionTestUtils.setField(analyticsService, "postHog", postHog);

        analyticsService.destroy();

        var ordered = inOrder(postHog);
        ordered.verify(postHog).flush();
        ordered.verify(postHog).close();
    }
}
