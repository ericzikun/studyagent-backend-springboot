package com.studyagent.api.controller.verla;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VerlaSseControllerTest {

    @Test
    void usesNewerBrowserHeaderWhenReconnectQueryCursorIsStale() {
        assertEquals(102L, VerlaSseController.parseLastEventId("102", 99L));
    }

    @Test
    void usesNewerQueryCursorAndToleratesMalformedHeader() {
        assertEquals(103L, VerlaSseController.parseLastEventId("101", 103L));
        assertEquals(103L, VerlaSseController.parseLastEventId("invalid", 103L));
        assertNull(VerlaSseController.parseLastEventId("invalid", null));
    }
}
