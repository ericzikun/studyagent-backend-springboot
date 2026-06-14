package com.studyagent.common.verla.dispatch;

import com.studyagent.common.verla.enums.VerlaCommandAction;

import java.util.Set;

/**
 * AI Detection / Humanizer 长任务：Java 侧派发前需占用全局并发 slot。
 */
public final class CapabilityRunDispatchActions {

    private static final Set<String> GATED_ACTIONS = Set.of(
            VerlaCommandAction.CMD_DETECTION_RUN.getCode(),
            VerlaCommandAction.CMD_HUMANIZER_RUN.getCode());

    private CapabilityRunDispatchActions() {
    }

    public static boolean isGated(String action) {
        return action != null && GATED_ACTIONS.contains(action);
    }
}
