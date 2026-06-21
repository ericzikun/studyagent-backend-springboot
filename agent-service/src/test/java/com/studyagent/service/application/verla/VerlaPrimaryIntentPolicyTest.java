package com.studyagent.service.application.verla;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerlaPrimaryIntentPolicyTest {

    @Test
    void shouldOverwritePrimaryIntent_whenExistingIntentIsEmpty() {
        assertTrue(VerlaTurnOrchestrator.shouldOverwritePrimaryIntent(null, "AI_DETECTION"));
        assertTrue(VerlaTurnOrchestrator.shouldOverwritePrimaryIntent("  ", "AI_HUMANIZER"));
    }

    @Test
    void shouldNotOverwritePrimaryIntent_whenIncomingMatchesExisting() {
        assertFalse(VerlaTurnOrchestrator.shouldOverwritePrimaryIntent("AI_DETECTION", "AI_DETECTION"));
    }

    @Test
    void shouldNotOverwritePrimaryIntent_forAiWritingCrossCall() {
        assertFalse(VerlaTurnOrchestrator.shouldOverwritePrimaryIntent(
                "AI_DETECTION", "AI_HUMANIZER"));
        assertFalse(VerlaTurnOrchestrator.shouldOverwritePrimaryIntent(
                "AI_HUMANIZER", "AI_DETECTION"));
    }

    @Test
    void shouldOverwritePrimaryIntent_forUnrelatedIntentChanges() {
        assertTrue(VerlaTurnOrchestrator.shouldOverwritePrimaryIntent(
                "ASSIGNMENT", "AI_DETECTION"));
    }
}
