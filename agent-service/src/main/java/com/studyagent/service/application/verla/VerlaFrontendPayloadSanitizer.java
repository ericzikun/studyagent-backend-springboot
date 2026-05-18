package com.studyagent.service.application.verla;

import com.studyagent.common.verla.enums.VerlaAgentEventType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces frontend-facing Verla event payloads without changing persisted session results.
 */
public final class VerlaFrontendPayloadSanitizer {

    private static final List<VisibleRequirementField> VISIBLE_REQUIREMENT_FIELDS = List.of(
            new VisibleRequirementField("subject", List.of("subject")),
            new VisibleRequirementField("academic_level", List.of("academic_level", "academicLevel")),
            new VisibleRequirementField("citation_style", List.of("citation_style", "citationStyle")),
            new VisibleRequirementField("estimated_length", List.of("estimated_length", "estimatedLength", "pageLength"))
    );

    private VerlaFrontendPayloadSanitizer() {
    }

    public static Map<String, Object> sanitize(String eventType, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return payload;
        }
        if (!shouldFilterRequirementForm(eventType)) {
            return payload;
        }
        if (isSchemaBackedRequirementForm(payload.get("requirementForm"))) {
            return payload;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(payload);
        sanitized.put("requirementForm", filterRequirementForm(payload.get("requirementForm")));
        return sanitized;
    }

    private static boolean shouldFilterRequirementForm(String eventType) {
        return VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED.name().equals(eventType)
                || VerlaAgentEventType.ASSIGNMENT_CLARIFY_FORM_READY.name().equals(eventType);
    }

    private static boolean isSchemaBackedRequirementForm(Object rawRequirementForm) {
        return rawRequirementForm instanceof Map<?, ?> rawMap && rawMap.containsKey("schema");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filterRequirementForm(Object rawRequirementForm) {
        if (!(rawRequirementForm instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> source = (Map<String, Object>) rawMap;
        Map<String, Object> visible = new LinkedHashMap<>();
        for (VisibleRequirementField field : VISIBLE_REQUIREMENT_FIELDS) {
            Object value = firstPresent(source, field.aliases());
            if (value != null) {
                visible.put(field.outputKey(), value);
            }
        }
        return visible;
    }

    private static Object firstPresent(Map<String, Object> source, List<String> aliases) {
        for (String alias : aliases) {
            if (source.containsKey(alias)) {
                return source.get(alias);
            }
        }
        return null;
    }

    private record VisibleRequirementField(String outputKey, List<String> aliases) {
    }
}
