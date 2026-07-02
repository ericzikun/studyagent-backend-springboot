package com.studyagent.service.application.verla.admin;

import com.studyagent.service.domain.verla.VerlaConversation;

import java.util.Locale;

/**
 * Maps {@code primary_intent} to frontend workspace task type for admin read-only view.
 */
public enum AdminConversationWorkspaceTaskType {

    ASSIGNMENT("assignment"),
    AI_DETECTION("ai-detection"),
    AI_HUMANIZER("humanizer"),
    UNKNOWN("unknown");

    private final String routeKey;

    AdminConversationWorkspaceTaskType(String routeKey) {
        this.routeKey = routeKey;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public static AdminConversationWorkspaceTaskType fromConversation(VerlaConversation conversation) {
        if (conversation == null) {
            return UNKNOWN;
        }
        String intent = conversation.getPrimaryIntent();
        if (intent == null || intent.isBlank()) {
            return ASSIGNMENT;
        }
        String normalized = intent.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ASSIGNMENT", "CREATE_ASSIGNMENT" -> ASSIGNMENT;
            case "AI_DETECTION" -> AI_DETECTION;
            case "AI_HUMANIZER" -> AI_HUMANIZER;
            default -> UNKNOWN;
        };
    }
}
