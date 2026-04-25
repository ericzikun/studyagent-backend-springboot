package com.studyagent.common.verla.enums;

import lombok.Getter;

import java.util.Set;

/**
 * Verla Py→Java 事件类型枚举
 * <p>
 * 对应文档 docs/verla-Java侧MVP技术方案.md §6.3 / §12 / §22.1
 */
@Getter
public enum VerlaAgentEventType {

    PLAN_INTENT_RESOLVED(false),
    PLAN_NEEDS_CLARIFY(false),

    AGENT_STARTED(false),
    AGENT_PLAN_DECOMPOSED(false),
    AGENT_STEP_STARTED(false),
    AGENT_STEP_STREAM_CHUNK(false),
    AGENT_STEP_PROGRESS(false),
    AGENT_STEP_COMPLETED(false),
    AGENT_BLOCK_ISSUED(false),
    AGENT_PROGRESS(false),
    AGENT_ARTIFACT_UPDATED(false),

    AGENT_COMPLETED(true),
    AGENT_FAILED(true),
    AGENT_CANCELLED(true);

    /**
     * 是否是 session 终态事件（用于 TerminalHandler 判定）
     */
    private final boolean terminal;

    VerlaAgentEventType(boolean terminal) {
        this.terminal = terminal;
    }

    private static final Set<VerlaAgentEventType> TERMINALS = Set.of(
        AGENT_COMPLETED, AGENT_FAILED, AGENT_CANCELLED);

    public static boolean isTerminal(VerlaAgentEventType type) {
        return TERMINALS.contains(type);
    }
}
