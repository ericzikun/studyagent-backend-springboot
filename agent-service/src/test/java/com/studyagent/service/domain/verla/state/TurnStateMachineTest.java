package com.studyagent.service.domain.verla.state;

import org.junit.jupiter.api.Test;

import static com.studyagent.service.domain.verla.state.TurnEvent.*;
import static com.studyagent.service.domain.verla.state.TurnStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Turn 状态机 happy path + 拒绝路径 + 终态幂等三类断言
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §11.2。
 */
class TurnStateMachineTest {

    private final TurnStateMachine sm = new TurnStateMachine();

    // -------- Happy paths --------
    @Test
    void plan_then_agent_complete() {
        assertEquals(PLANNING, sm.next(CREATED, SUBMIT));
        assertEquals(DISPATCHING, sm.next(PLANNING, PLAN_OK));
        assertEquals(RUNNING_AGENT, sm.next(DISPATCHING, START_AGENT));
        assertEquals(COMPLETED, sm.next(RUNNING_AGENT, AGENT_OK));
    }

    @Test
    void skip_plan_path() {
        assertEquals(DISPATCHING, sm.next(CREATED, SKIP_PLAN));
    }

    @Test
    void clarify_then_resubmit() {
        assertEquals(AWAITING_CLARIFY, sm.next(PLANNING, PLAN_CLARIFY));
        assertEquals(PLANNING, sm.next(AWAITING_CLARIFY, SUBMIT));
    }

    @Test
    void cancel_during_running() {
        assertEquals(CANCELLING, sm.next(RUNNING_AGENT, USER_CANCEL));
        assertEquals(CANCELLED, sm.next(CANCELLING, CANCEL_CONFIRMED));
    }

    @Test
    void late_agent_ok_during_cancelling_resolves_to_cancelled() {
        // 用户取消已发出，agent 又回了 OK：仍按 CANCELLED 落定
        assertEquals(CANCELLED, sm.next(CANCELLING, AGENT_OK));
    }

    @Test
    void watchdog_timeout_in_running_to_failed() {
        assertEquals(FAILED, sm.next(RUNNING_AGENT, WATCHDOG_TIMEOUT));
    }

    // -------- Reject paths --------
    @Test
    void invalid_transition_throws() {
        assertThrows(IllegalStateException.class,
                () -> sm.next(CREATED, AGENT_OK));
        assertThrows(IllegalStateException.class,
                () -> sm.next(PLANNING, START_AGENT));
        assertThrows(IllegalStateException.class,
                () -> sm.next(AWAITING_CLARIFY, AGENT_OK));
    }

    // -------- Terminal idempotency --------
    @Test
    void terminal_states_idempotent() {
        for (TurnStatus terminal : new TurnStatus[]{COMPLETED, FAILED, CANCELLED}) {
            assertTrue(terminal.isTerminal());
            for (TurnEvent any : TurnEvent.values()) {
                assertEquals(terminal, sm.next(terminal, any),
                        "terminal=" + terminal + " event=" + any);
            }
        }
    }
}
