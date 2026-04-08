package com.studyagent.api.service;

import com.studyagent.service.domain.user.ClerkClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailNotificationServiceTest {

    @Test
    void buildSubjectShouldStripLineBreaksAndCollapseWhitespace() {
        String subject = EmailNotificationService.buildSubject("Line one\n\n  Line two\r\nLine three");

        assertFalse(subject.contains("\n"));
        assertFalse(subject.contains("\r"));
        assertTrue(subject.contains("Line one Line two Line three"));
    }

    @Test
    void buildSubjectShouldTruncateLongTitles() {
        String subject = EmailNotificationService.buildSubject("A".repeat(140));

        assertTrue(subject.startsWith("Task complete — "));
        assertTrue(subject.endsWith("..."));
        assertTrue(subject.length() <= 120);
    }

    @Test
    void buildEmailHtmlShouldUseCidLogo() {
        EmailNotificationService service = new EmailNotificationService(new NoopClerkClient(), WebClient.builder().build());

        String html = ReflectionTestUtils.invokeMethod(service, "buildEmailHtml", "Task", "Apr 8, 10:00 AM", "https://verla.io/workflow?taskId=1");

        assertTrue(html.contains("href=\"https://verla.io\""));
        assertTrue(html.contains("src=\"cid:verla-logo\""));
        assertTrue(html.contains("alt=\"Verla\""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildInlineLogoAttachmentShouldIncludeCidAndBase64Content() {
        EmailNotificationService service = new EmailNotificationService(new NoopClerkClient(), WebClient.builder().build());

        Map<String, Object> attachment = ReflectionTestUtils.invokeMethod(service, "buildInlineLogoAttachment");

        assertNotNull(attachment);
        assertTrue("notification-icon.png".equals(attachment.get("filename")));
        assertTrue("image/png".equals(attachment.get("content_type")));
        assertTrue("verla-logo".equals(attachment.get("content_id")));
        assertTrue(((String) attachment.get("content")).startsWith("iVBORw0KGgo"));
    }

    private static class NoopClerkClient implements ClerkClient {
        @Override
        public UserInfo verifyToken(String token) {
            return null;
        }

        @Override
        public String getUserEmail(String clerkUserId) {
            return null;
        }

        @Override
        public com.studyagent.service.domain.user.User getOrCreateUser(String clerkUserId) {
            return null;
        }
    }
}
