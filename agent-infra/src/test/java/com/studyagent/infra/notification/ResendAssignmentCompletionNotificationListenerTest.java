package com.studyagent.infra.notification;

import com.studyagent.service.application.verla.notification.AssignmentCompletionNotificationEvent;
import com.studyagent.service.domain.user.ClerkClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResendAssignmentCompletionNotificationListenerTest {

    @Test
    @SuppressWarnings("unchecked")
    void buildResendTemplateBodyShouldUseTemplateVariablesAndCanonicalUrl() {
        ResendAssignmentCompletionNotificationListener listener =
                new ResendAssignmentCompletionNotificationListener(
                        Mockito.mock(ClerkClient.class), WebClient.builder().build());
        ReflectionTestUtils.setField(listener, "frontendUrl", "https://verla.io/");
        ReflectionTestUtils.setField(listener, "assignmentCompletedTemplateId", "tpl_assignment_done");

        AssignmentCompletionNotificationEvent event = new AssignmentCompletionNotificationEvent(
                385L, 900L, 1000L, "user_1", "  Line one\nLine two  ");

        Map<String, Object> body = listener.buildResendTemplateBody("student@example.com", event);

        assertEquals(List.of("student@example.com"), body.get("to"));
        assertFalse(body.containsKey("from"));
        assertFalse(body.containsKey("subject"));
        assertFalse(body.containsKey("html"));
        assertFalse(body.containsKey("text"));
        assertFalse(body.containsKey("react"));

        Map<String, Object> template = (Map<String, Object>) body.get("template");
        assertEquals("tpl_assignment_done", template.get("id"));
        Map<String, Object> variables = (Map<String, Object>) template.get("variables");
        assertEquals("Line one Line two", variables.get("title"));
        String url = (String) variables.get("url");
        assertTrue(url.startsWith("https://verla.io/assignments/vc_"));
    }

    @Test
    void buildIdempotencyKeyShouldUseSessionId() {
        AssignmentCompletionNotificationEvent event = new AssignmentCompletionNotificationEvent(
                385L, 900L, 1000L, "user_1", "Title");

        assertEquals("assignment-completed:1000",
                ResendAssignmentCompletionNotificationListener.buildIdempotencyKey(event));
    }
}
