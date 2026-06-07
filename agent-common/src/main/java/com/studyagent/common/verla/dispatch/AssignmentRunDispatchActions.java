package com.studyagent.common.verla.dispatch;

import com.studyagent.common.verla.enums.VerlaCommandAction;

import java.util.Set;

/**
 * 作业 Workforce 主执行命令：Java 侧派发前需占用全局并发 slot。
 * <p>
 * 与历史 Py {@code AssignmentRunConcurrencyGate} 覆盖范围对齐。
 */
public final class AssignmentRunDispatchActions {

    private static final Set<String> GATED_ACTIONS = Set.of(
            VerlaCommandAction.CMD_ASSIGNMENT_RUN.getCode(),
            VerlaCommandAction.CMD_AGENT_RETRY.getCode());

    private AssignmentRunDispatchActions() {
    }

    public static boolean isGated(String action) {
        return action != null && GATED_ACTIONS.contains(action);
    }
}
