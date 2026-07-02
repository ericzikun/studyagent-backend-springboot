package com.studyagent.infra.notification;

import com.studyagent.common.verla.id.VerlaPublicIdMapper;
import com.studyagent.service.application.verla.notification.AssignmentCompletionNotificationEvent;
import com.studyagent.service.domain.user.ClerkClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends V2 Assignment completion emails through Resend templates.
 *
 * This listener owns provider-specific details only. The Assignment runtime
 * publishes a completion event after the turn finishes; delivery remains
 * best-effort and must never change the completed task state.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResendAssignmentCompletionNotificationListener {

    private static final String DEFAULT_TITLE = "Your Research";
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final ClerkClient clerkClient;
    private final WebClient webClient;

    @Value("${notification.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${notification.email.resend-api-key:}")
    private String resendApiKey;

    @Value("${notification.email.frontend-url:https://verla.io}")
    private String frontendUrl;

    @Value("${notification.email.assignment-completed-template-id:}")
    private String assignmentCompletedTemplateId;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssignmentCompleted(AssignmentCompletionNotificationEvent event) {
        try {
            if (!emailEnabled) {
                log.debug("Assignment completion email disabled, skip: conversationId={}, sessionId={}",
                        event.conversationId(), event.sessionId());
                return;
            }
            if (isBlank(resendApiKey)) {
                log.warn("RESEND_API_KEY not configured, skip Assignment completion email: conversationId={}, sessionId={}",
                        event.conversationId(), event.sessionId());
                return;
            }
            if (isBlank(assignmentCompletedTemplateId)) {
                log.warn("Assignment completion email template id not configured, skip: conversationId={}, sessionId={}",
                        event.conversationId(), event.sessionId());
                return;
            }
            if (isBlank(event.clerkUserId())) {
                log.warn("Assignment completion event has no user, skip email: conversationId={}, sessionId={}",
                        event.conversationId(), event.sessionId());
                return;
            }

            String userEmail = clerkClient.getUserEmail(event.clerkUserId());
            if (isBlank(userEmail)) {
                log.warn("Unable to resolve user email, skip Assignment completion email: conversationId={}, clerkUserId={}",
                        event.conversationId(), event.clerkUserId());
                return;
            }

            Map<String, Object> body = buildResendTemplateBody(userEmail, event);
            webClient.post()
                    .uri("https://api.resend.com/emails")
                    .header("Authorization", "Bearer " + resendApiKey)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", buildIdempotencyKey(event))
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(SEND_TIMEOUT)
                    .subscribe(
                            response -> log.info("Assignment completion email sent: conversationId={}, sessionId={}, to={}, resendId={}",
                                    event.conversationId(), event.sessionId(), userEmail, response.get("id")),
                            error -> log.warn("Assignment completion email failed: conversationId={}, sessionId={}, to={}, error={}",
                                    event.conversationId(), event.sessionId(), userEmail, error.getMessage())
                    );
        } catch (Exception e) {
            log.warn("Assignment completion email exception: conversationId={}, sessionId={}, error={}",
                    event.conversationId(), event.sessionId(), e.getMessage());
        }
    }

    Map<String, Object> buildResendTemplateBody(String to, AssignmentCompletionNotificationEvent event) {
        String title = normalizeTitle(event.title());
        Map<String, Object> variables = new HashMap<>();
        variables.put("title", title);
        variables.put("url", buildAssignmentUrl(event.conversationId()));

        Map<String, Object> template = new HashMap<>();
        template.put("id", assignmentCompletedTemplateId);
        template.put("variables", variables);

        Map<String, Object> body = new HashMap<>();
        body.put("to", List.of(to));
        body.put("template", template);
        return body;
    }

    String buildAssignmentUrl(Long conversationId) {
        String publicId = VerlaPublicIdMapper.conversation(conversationId);
        String base = isBlank(frontendUrl) ? "https://verla.io" : frontendUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/assignments/" + publicId;
    }

    static String buildIdempotencyKey(AssignmentCompletionNotificationEvent event) {
        return "assignment-completed:" + event.sessionId();
    }

    static String normalizeTitle(String title) {
        if (isBlank(title)) {
            return DEFAULT_TITLE;
        }
        String normalized = title
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return isBlank(normalized) ? DEFAULT_TITLE : normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
