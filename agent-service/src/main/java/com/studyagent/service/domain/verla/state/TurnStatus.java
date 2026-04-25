package com.studyagent.service.domain.verla.state;

import java.util.EnumSet;
import java.util.Set;

/**
 * Verla Turn 状态枚举
 * <p>
 * 与 verla_turns.status 列字面量完全一致。
 * 状态机详见 docs/verla-Java侧MVP技术方案.md §11.2。
 *
 * <pre>
 *  CREATED → PLANNING → AWAITING_CLARIFY → PLANNING → DISPATCHING → RUNNING_AGENT → COMPLETED
 *           └─ SKIP_PLAN ───────────────────────────────────────────┘
 * </pre>
 */
public enum TurnStatus {

    CREATED,
    PLANNING,
    AWAITING_CLARIFY,
    DISPATCHING,
    RUNNING_AGENT,
    COMPLETED,
    FAILED,
    CANCELLING,
    CANCELLED;

    private static final Set<TurnStatus> TERMINAL =
            EnumSet.of(COMPLETED, FAILED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
