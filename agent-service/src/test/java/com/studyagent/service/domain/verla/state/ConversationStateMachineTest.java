package com.studyagent.service.domain.verla.state;

import org.junit.jupiter.api.Test;

import static com.studyagent.service.domain.verla.state.ConversationStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationStateMachineTest {

    private final ConversationStateMachine sm = new ConversationStateMachine();

    @Test
    void archive_and_restore() {
        assertEquals(ARCHIVED, sm.archive(ACTIVE));
        assertEquals(ACTIVE, sm.restore(ARCHIVED));
    }

    @Test
    void delete_is_idempotent_from_any_state() {
        assertEquals(DELETED, sm.delete(ACTIVE));
        assertEquals(DELETED, sm.delete(ARCHIVED));
        assertEquals(DELETED, sm.delete(DELETED));
    }

    @Test
    void archive_from_archived_throws() {
        assertThrows(IllegalStateException.class, () -> sm.archive(ARCHIVED));
        assertThrows(IllegalStateException.class, () -> sm.restore(ACTIVE));
    }
}
