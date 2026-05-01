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
 *  CREATED → PLANNING → AWAITING_ASSIGN_CONFIRM → DISPATCHING → RUNNING_AGENT → COMPLETED
 *           └→ AWAITING_CLARIFY → PLANNING ──────────────────────────────────────────┘
 *           └─ SKIP_PLAN ───────────────────────────────────────────┘
 * </pre>
 */
public enum TurnStatus {

    CREATED,
    PLANNING,
    /** 作业意图 Plan 已收敛，等待前端用一条「伪装」用户消息提交 JSON 确认后再派发 agent */
    AWAITING_ASSIGN_CONFIRM,
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
