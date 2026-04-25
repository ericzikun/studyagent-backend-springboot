package com.studyagent.service.domain.verla.state;

import java.util.EnumSet;
import java.util.Set;

/**
 * Verla Session 状态枚举
 * <p>
 * 与 verla_sessions.status 列字面量完全一致。
 * 状态机详见 docs/verla-Java侧MVP技术方案.md §11.3。
 *
 * <p>
 * 注：文档 §11.3 中提到的 STREAMING 在 MVP 中合并到 RUNNING（仅靠 last_progress_at 区分），
 * 避免与 SQL schema 不一致；DISPATCHED 不存在，只有 DISPATCHING（写 outbox 后即进入）。
 */
public enum SessionStatus {

    CREATED,
    DISPATCHING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLING,
    CANCELLED;

    private static final Set<SessionStatus> TERMINAL =
            EnumSet.of(SUCCEEDED, FAILED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
