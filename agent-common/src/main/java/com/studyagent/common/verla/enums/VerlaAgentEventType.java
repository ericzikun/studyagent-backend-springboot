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

    /** Assignment 域 —— 阶段一：需求理解（Py assignment_init_service） */
    ASSIGNMENT_INIT_STARTED(false),
    ASSIGNMENT_INIT_STREAM_CHUNK(false),
    ASSIGNMENT_INIT_COMPLETED(true),
    ASSIGNMENT_INIT_FAILED(true),

    /** Assignment 域 —— 阶段二：需求深入（Py assignment_deep_understanding_service） */
    ASSIGNMENT_DEEP_UNDERSTANDING_STARTED(false),
    ASSIGNMENT_DEEP_UNDERSTANDING_STREAM_CHUNK(false),
    ASSIGNMENT_CLARIFY_FORM_READY(false),
    ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED(true),
    ASSIGNMENT_DEEP_UNDERSTANDING_FAILED(true),

    /** Assignment 域 —— 阶段三：需求澄清 / 表单构建 */
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

    /** Assignment AgentFlow 域（Py assignment_flow_service 新事件体系） */
    ASSIGNMENT_AGENT_FLOW_STARTED(false),
    /** Assignment workflow canvas 节点快照更新，payload.node 直接透传给前端右栏任务卡片。 */
    ASSIGNMENT_AGENT_NODE_UPDATED(false),
    /** Assignment workflow canvas 节点快照更新的兼容命名。 */
    ASSIGNMENT_WORKFLOW_NODE_UPDATED(false),
    ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED(false),
    ASSIGNMENT_AGENT_FLOW_COMPLETED(true),
    ASSIGNMENT_AGENT_FLOW_FAILED(true),
    ASSIGNMENT_AGENT_FLOW_CANCELLED(true),

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
            ASSIGNMENT_INIT_COMPLETED,
            ASSIGNMENT_INIT_FAILED,
            ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED,
            ASSIGNMENT_DEEP_UNDERSTANDING_FAILED,
            ASSIGNMENT_CLARIFY_COMPLETED,
            ASSIGNMENT_CLARIFY_FAILED,
            ASSIGNMENT_CLARIFY_CANCELLED,
            ASSIGNMENT_COMPLETED,
            ASSIGNMENT_FAILED,
            ASSIGNMENT_CANCELLED,
            ASSIGNMENT_AGENT_FLOW_COMPLETED,
            ASSIGNMENT_AGENT_FLOW_FAILED,
            ASSIGNMENT_AGENT_FLOW_CANCELLED,
            MATERIALS_COMPLETED);

    public static boolean isTerminal(VerlaAgentEventType type) {
        return TERMINALS.contains(type);
    }
}
