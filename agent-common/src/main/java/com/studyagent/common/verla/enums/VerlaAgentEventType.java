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
    /** Py plan runner：开始意图解析（可选观测事件） */
    PLAN_INTENT_STARTED(false),
    /** Py plan runner：意图解析对话流式增量 */
    PLAN_INTENT_STREAM_CHUNK(false),

    /** Assignment 域 —— 需求理解 / 澄清入口（Py assignment_clarify_service） */
    ASSIGNMENT_CLARIFY_STARTED(false),
    ASSIGNMENT_CLARIFY_STREAM_CHUNK(false),
    ASSIGNMENT_CLARIFY_COMPLETED(true),
    ASSIGNMENT_CLARIFY_FAILED(true),
    ASSIGNMENT_CLARIFY_CANCELLED(true),

    /** Assignment 域 —— 需求理解子阶段 */
    ASSIGNMENT_REQUIREMENT_UNDERSTANDING_STARTED(false),
    ASSIGNMENT_REQUIREMENT_UNDERSTANDING_PROGRESS(false),
    ASSIGNMENT_REQUIREMENT_UNDERSTANDING_COMPLETED(false),

    /** Assignment 域 —— 整体生命周期（Py assignment_flow_service） */
    ASSIGNMENT_STARTED(false),
    ASSIGNMENT_PLAN_DECOMPOSED(false),
    ASSIGNMENT_PROGRESS(false),
    ASSIGNMENT_ARTIFACT_UPDATED(false),
    ASSIGNMENT_COMPLETED(true),
    ASSIGNMENT_FAILED(true),
    ASSIGNMENT_CANCELLED(true),

    /** Materials 域 */
    MATERIALS_STARTED(false),
    MATERIALS_COMPLETED(true),

    AGENT_STARTED(false),
    AGENT_PLAN_DECOMPOSED(false),
    AGENT_STEP_STARTED(false),
    AGENT_STEP_STREAM_CHUNK(false),
    AGENT_STEP_PROGRESS(false),
    AGENT_STEP_COMPLETED(false),
    AGENT_BLOCK_ISSUED(false),
    AGENT_PROGRESS(false),
    AGENT_ARTIFACT_UPDATED(false),

    /** V2: agent 工具调用 trace（visibility=INTERNAL/USER_VISIBLE） */
    AGENT_TOOL_CALL_RECORDED(false),
    /** V2: agent 发起澄清问卷 */
    AGENT_CLARIFY_FORM_ISSUED(false),
    /** V2: 上传附件解析完成（含 PARSING / PARSED / FAILED 三种 status） */
    ATTACHMENT_PARSED(false),

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
            AGENT_COMPLETED,
            AGENT_FAILED,
            AGENT_CANCELLED,
            ASSIGNMENT_CLARIFY_COMPLETED,
            ASSIGNMENT_CLARIFY_FAILED,
            ASSIGNMENT_CLARIFY_CANCELLED,
            ASSIGNMENT_COMPLETED,
            ASSIGNMENT_FAILED,
            ASSIGNMENT_CANCELLED,
            MATERIALS_COMPLETED);

    public static boolean isTerminal(VerlaAgentEventType type) {
        return TERMINALS.contains(type);
    }
}
