package com.studyagent.service.application.verla;

import com.studyagent.common.verla.enums.VerlaAgentEventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class VerlaFrontendPayloadSanitizerTest {

    @Test
    void sanitize_deepUnderstanding_keepsOnlyVisibleRequirementFieldsAndAppendAsk() {
        Map<String, Object> appendAsk = Map.of(
                "questions", List.of(Map.of("question", "Which focus should we use?")));
        Map<String, Object> payload = Map.of(
                "content", "Ready",
                "requirementForm", Map.of(
                        "task_title", "Literature Review",
                        "requirement_understanding", "Write a literature review.",
                        "subject", "Chemistry",
                        "academicLevel", "Undergraduate",
                        "citation_style", "APA",
                        "pageLength", "6 pages",
                        "rubric", "Not specified",
                        "input_relationship_analysis", "The user provided clear instructions."),
                "appendAsk", appendAsk);

        Map<String, Object> sanitized = VerlaFrontendPayloadSanitizer.sanitize(
                VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED.name(), payload);

        assertEquals(appendAsk, sanitized.get("appendAsk"));
        assertEquals(Map.of(
                "subject", "Chemistry",
                "academic_level", "Undergraduate",
                "citation_style", "APA",
                "estimated_length", "6 pages"), sanitized.get("requirementForm"));
        assertFalse(((Map<?, ?>) sanitized.get("requirementForm")).containsKey("task_title"));
        assertFalse(((Map<?, ?>) sanitized.get("requirementForm")).containsKey("rubric"));
    }

    @Test
    void sanitize_clarifyFormReady_keepsOnlyVisibleRequirementFieldsForFrontend() {
        Map<String, Object> payload = Map.of(
                "requirementForm", Map.of(
                        "task_title", "Literature Review",
                        "requirement_understanding", "Write a literature review.",
                        "subject", "Chemistry",
                        "academic_level", "Undergraduate",
                        "citationStyle", "APA",
                        "estimatedLength", "6 pages",
                        "rubric", "Not specified"),
                "appendAsk", Map.of("questions", List.of()));

        Map<String, Object> sanitized = VerlaFrontendPayloadSanitizer.sanitize(
                VerlaAgentEventType.ASSIGNMENT_CLARIFY_FORM_READY.name(), payload);

        assertEquals(Map.of(
                "subject", "Chemistry",
                "academic_level", "Undergraduate",
                "citation_style", "APA",
                "estimated_length", "6 pages"), sanitized.get("requirementForm"));
        assertEquals(Map.of("questions", List.of()), sanitized.get("appendAsk"));
        assertFalse(((Map<?, ?>) sanitized.get("requirementForm")).containsKey("task_title"));
        assertFalse(((Map<?, ?>) sanitized.get("requirementForm")).containsKey("rubric"));
    }

    @Test
    void sanitize_nonDeepUnderstandingEvent_returnsOriginalPayload() {
        Map<String, Object> payload = Map.of("requirementForm", Map.of("rubric", "full"));

        Map<String, Object> sanitized = VerlaFrontendPayloadSanitizer.sanitize(
                VerlaAgentEventType.ASSIGNMENT_CLARIFY_COMPLETED.name(), payload);

        assertSame(payload, sanitized);
    }

    @Test
    void sanitize_clarifyFormReady_keepsFullFormPayload() {
        Map<String, Object> payload = Map.of(
                "requirementForm", Map.of(
                        "title", "Assignment requirements",
                        "schema", List.of(Map.of("key", "subject", "label", "Subject"))));

        Map<String, Object> sanitized = VerlaFrontendPayloadSanitizer.sanitize(
                VerlaAgentEventType.ASSIGNMENT_CLARIFY_FORM_READY.name(), payload);

        assertSame(payload, sanitized);
    }

    @Test
    void sanitize_keepsFieldsDefinitionArrayWithVisibleValues() {
        List<Map<String, Object>> fields = List.of(
                Map.of("backendKey", "subject", "label", "科目", "type", "text", "order", 1),
                Map.of("backendKey", "deliverable_count", "label", "成果物の種類", "type", "multiple_choice",
                        "options", List.of("writing", "ppt", "coding"), "order", 2));
        Map<String, Object> payload = Map.of(
                "requirementForm", Map.of(
                        "fields", fields,
                        "subject", "历史",
                        "academic_level", "Not specified",
                        "citation_style", "Not specified",
                        "estimated_length", "1000字",
                        "task_title", "Hidden title",
                        "rubric", "Not specified"),
                "appendAsk", Map.of("questions", List.of()));

        Map<String, Object> sanitized = VerlaFrontendPayloadSanitizer.sanitize(
                VerlaAgentEventType.ASSIGNMENT_CLARIFY_FORM_READY.name(), payload);

        Map<?, ?> form = (Map<?, ?>) sanitized.get("requirementForm");
        // 白名单字段值仍保留
        assertEquals("历史", form.get("subject"));
        assertEquals("1000字", form.get("estimated_length"));
        // fields 定义数组（含本地化 label）保留，供前端渲染
        assertEquals(fields, form.get("fields"));
        // 白名单外字段仍被过滤
        assertFalse(form.containsKey("task_title"));
        assertFalse(form.containsKey("rubric"));
    }
}
