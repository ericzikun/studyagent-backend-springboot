package com.studyagent.service.domain.verla.state;

import org.junit.jupiter.api.Test;

import static com.studyagent.service.domain.verla.state.SessionEvent.*;
import static com.studyagent.service.domain.verla.state.SessionStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Session 状态机覆盖 happy path / cancel race / watchdog / reject。
 */
class SessionStateMachineTest {

    private final SessionStateMachine sm = new SessionStateMachine();

    @Test
    void happy_path_all_events() {
        assertEquals(DISPATCHING, sm.next(CREATED, DISPATCH));
        assertEquals(RUNNING, sm.next(DISPATCHING, AGENT_STARTED));
        assertEquals(RUNNING, sm.next(RUNNING, STREAM_TICK));
        assertEquals(SUCCEEDED, sm.next(RUNNING, AGENT_COMPLETED));
    }

    @Test
    void cancel_race_late_completion_resolves_to_cancelled() {
        assertEquals(CANCELLING, sm.next(RUNNING, USER_CANCEL));
        assertEquals(CANCELLED, sm.next(CANCELLING, AGENT_COMPLETED));
        assertEquals(CANCELLED, sm.next(CANCELLING, AGENT_FAILED));
        assertEquals(CANCELLED, sm.next(CANCELLING, AGENT_CANCELLED));
    }

    @Test
    void watchdog_in_dispatching_and_running_to_failed() {
        assertEquals(FAILED, sm.next(DISPATCHING, WATCHDOG_TIMEOUT));
        assertEquals(FAILED, sm.next(RUNNING, WATCHDOG_TIMEOUT));
    }

    @Test
    void ack_fail_in_dispatching_to_failed() {
        assertEquals(FAILED, sm.next(DISPATCHING, ACK_FAIL));
    }

    @Test
    void reject_invalid_event() {
        assertThrows(IllegalStateException.class,
                () -> sm.next(CREATED, AGENT_COMPLETED));
        assertThrows(IllegalStateException.class,
                () -> sm.next(RUNNING, DISPATCH));
    }

    @Test
    void terminal_states_idempotent() {
        for (SessionStatus terminal : new SessionStatus[]{SUCCEEDED, FAILED, CANCELLED}) {
            assertTrue(terminal.isTerminal());
            for (SessionEvent any : SessionEvent.values()) {
                assertEquals(terminal, sm.next(terminal, any));
            }
        }
    }
}
