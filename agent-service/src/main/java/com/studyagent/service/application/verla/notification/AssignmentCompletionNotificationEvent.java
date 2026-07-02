package com.studyagent.service.application.verla.notification;

/**
 * Event emitted after a V2 Assignment generation run completes for the first time.
 *
 * The service layer only publishes the platform-neutral completion context.
 * Infrastructure listeners resolve email delivery details such as Resend
 * template IDs, public URLs, and provider credentials.
 */
public record AssignmentCompletionNotificationEvent(
        Long conversationId,
        Long turnId,
        Long sessionId,
        String clerkUserId,
        String title) {
}
