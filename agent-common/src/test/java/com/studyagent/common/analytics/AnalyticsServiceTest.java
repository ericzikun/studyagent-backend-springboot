package com.studyagent.common.analytics;

import com.posthog.java.PostHog;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnalyticsServiceTest {

    @Test
    void capture_adds_environment_and_app_version_defaults() {
        AnalyticsService analyticsService = new AnalyticsService();
        PostHog postHog = mock(PostHog.class);
        ReflectionTestUtils.setField(analyticsService, "enabled", true);
        ReflectionTestUtils.setField(analyticsService, "environment", "staging");
        ReflectionTestUtils.setField(analyticsService, "appVersion", "v2");
        ReflectionTestUtils.setField(analyticsService, "postHog", postHog);

        analyticsService.capture("user_1", "assignment:generation:started", Map.of(
                "conversation_id", 418L,
                "task_type", "assignment"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> propsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(postHog).capture(org.mockito.ArgumentMatchers.eq("user_1"),
                org.mockito.ArgumentMatchers.eq("assignment:generation:started"),
                propsCaptor.capture());

        assertThat(propsCaptor.getValue())
                .containsEntry("conversation_id", 418L)
                .containsEntry("task_type", "assignment")
                .containsEntry("environment", "staging")
                .containsEntry("app_version", "v2");
    }
}
