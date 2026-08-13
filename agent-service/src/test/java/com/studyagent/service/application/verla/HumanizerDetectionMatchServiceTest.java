package com.studyagent.service.application.verla;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanizerDetectionMatchServiceTest {

    @Test
    void normalizeCollapsesWhitespace() {
        String n = HumanizerDetectionMatchService.normalizeText("Hello\r\n\n  world\t!");
        assertEquals("Hello world !", n);
    }

    @Test
    void containmentMatchesDeletedMiddleWhenRatioOk() {
        String base = ("alpha bravo charlie delta echo foxtrot golf hotel india juliet "
                + "kilo lima mike november oscar papa quebec romeo sierra tango ").repeat(3);
        String shorter = base.substring(0, (int) (base.length() * 0.75));
        assertTrue(HumanizerDetectionMatchService.isContainmentMatch(
                HumanizerDetectionMatchService.normalizeText(shorter),
                HumanizerDetectionMatchService.normalizeText(base)));
    }

    @Test
    void jaccardMatchesLightDeletion() {
        String base = ("The quick brown fox jumps over the lazy dog while scientists "
                + "study climate patterns across continental regions carefully. ").repeat(4);
        String edited = base.replace("quick brown ", "").replace("carefully. ", ".");
        assertTrue(HumanizerDetectionMatchService.isJaccardMatch(
                HumanizerDetectionMatchService.normalizeText(edited),
                HumanizerDetectionMatchService.normalizeText(base)));
    }

    @Test
    void jaccardRejectsUnrelatedText() {
        String a = ("Alpha text about biology and chemistry experiments in modern labs "
                + "continues with detailed methodology and careful observations. ").repeat(3);
        String b = ("Completely different narrative about basketball tournaments and "
                + "stadium construction budgets for municipal projects worldwide. ").repeat(3);
        assertFalse(HumanizerDetectionMatchService.isJaccardMatch(
                HumanizerDetectionMatchService.normalizeText(a),
                HumanizerDetectionMatchService.normalizeText(b)));
    }
}
