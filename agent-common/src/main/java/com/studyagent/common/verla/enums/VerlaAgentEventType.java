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
    /** Py task_name runner：对话标题生成完成（cmd.plan.task_name session 的终态事件） */
    PLAN_TASK_NAME_RESOLVED(true),
    /** Py task_name runner：标题生成失败（best-effort，建议作非阻断信号处理） */
    PLAN_TASK_NAME_FAILED(true),

    /** Assignment 域 —— 阶段一：需求理解（Py assignment_init_service） */
    ASSIGNMENT_INIT_STARTED(false),
    ASSIGNMENT_INIT_STREAM_CHUNK(false),
    ASSIGNMENT_INIT_COMPLETED(true),
    ASSIGNMENT_INIT_FAILED(true),
    ASSIGNMENT_INIT_CANCELLED(true),

    /** Assignment 域 —— 阶段二：需求深入（Py assignment_deep_understanding_service） */
    ASSIGNMENT_DEEP_UNDERSTANDING_STARTED(false),
    ASSIGNMENT_DEEP_UNDERSTANDING_STREAM_CHUNK(false),
    ASSIGNMENT_CLARIFY_FORM_READY(false),
    ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED(true),
    ASSIGNMENT_DEEP_UNDERSTANDING_FAILED(true),
    ASSIGNMENT_DEEP_UNDERSTANDING_CANCELLED(true),

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
    /** Java 派发门控：run 命令在 outbox 中等待并发 slot（非 Py 上报） */
    ASSIGNMENT_RUN_DISPATCH_QUEUED(false),
    /** Java 派发门控：run/retry 已成功发往 MQ，等待 Python 启动（非 Py 上报） */
    ASSIGNMENT_RUN_DISPATCHED(false),
    ASSIGNMENT_AGENT_FLOW_STARTED(false),
    /** Assignment workflow canvas 节点快照更新，payload.node 直接透传给前端右栏任务卡片。 */
    ASSIGNMENT_AGENT_NODE_UPDATED(false),
    /** Assignment workflow canvas 节点快照更新的兼容命名。 */
    ASSIGNMENT_WORKFLOW_NODE_UPDATED(false),
    /** Assignment workforce 任务节点流式 detail（产出文本 + detailChunk 累积）。 */
    ASSIGNMENT_AGENT_NODE_DETAILED(false),
    ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED(false),
    ASSIGNMENT_AGENT_FLOW_COMPLETED(true),
    ASSIGNMENT_AGENT_FLOW_FAILED(true),
    ASSIGNMENT_AGENT_FLOW_CANCELLED(true),

    /** Materials 域 */
    MATERIALS_STARTED(false),
    MATERIALS_COMPLETED(true),

    /** File chat 域 */
    FILE_CHAT_STARTED(false),
    FILE_CHAT_STREAM_CHUNK(false),
    FILE_CHAT_COMPLETED(true),
    FILE_CHAT_FAILED(true),
    FILE_CHAT_CANCELLED(true),

    /** Chat With Assignment 域 —— chat turn 生命周期（read / write 共用） */
    ASSIGNMENT_CHAT_STARTED(false),
    ASSIGNMENT_CHAT_STREAM_CHUNK(false),
    ASSIGNMENT_CHAT_COMPLETED(true),
    ASSIGNMENT_CHAT_FAILED(true),
    ASSIGNMENT_CHAT_CANCELLED(true),

    /**
     * Chat With Assignment 域 —— edit proposal 生命周期（write 专用，来自 chat_with_assignment协议）。
     * 三者均非终态：它们是 chat turn 内部的编辑子生命周期，turn 仍以 ASSIGNMENT_CHAT_COMPLETED 收尾。
     */
    ARTIFACT_EDIT_PROPOSAL_STARTED(false),
    ARTIFACT_EDIT_PROPOSAL_READY(false),
    ARTIFACT_EDIT_PROPOSAL_FAILED(false),

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
    AGENT_CANCELLED(true),

    /** Java 派发门控：AI Detection run 在 outbox 中等待并发 slot（非 Py 上报） */
    AI_DETECTION_RUN_DISPATCH_QUEUED(false),
    /** Java 派发门控：AI Detection run 已成功发往 MQ，等待 Python 启动（非 Py 上报） */
    AI_DETECTION_RUN_DISPATCHED(false),
    /** Java 派发门控：AI Humanizer run 在 outbox 中等待并发 slot（非 Py 上报） */
    AI_HUMANIZER_RUN_DISPATCH_QUEUED(false),
    /** Java 派发门控：AI Humanizer run 已成功发往 MQ，等待 Python 启动（非 Py 上报） */
    AI_HUMANIZER_RUN_DISPATCHED(false),

    /** AI Detection / Humanizer 域（Py runtime 级终态；业务流内失败也可能走 AGENT_FAILED） */
    AI_DETECTION_COMPLETED(true),
    AI_DETECTION_FAILED(true),
    AI_DETECTION_CANCELLED(true),
    AI_HUMANIZER_COMPLETED(true),
    AI_HUMANIZER_FAILED(true),
    AI_HUMANIZER_CANCELLED(true);

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
            ASSIGNMENT_INIT_CANCELLED,
            ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED,
            ASSIGNMENT_DEEP_UNDERSTANDING_FAILED,
            ASSIGNMENT_DEEP_UNDERSTANDING_CANCELLED,
            ASSIGNMENT_CLARIFY_COMPLETED,
            ASSIGNMENT_CLARIFY_FAILED,
            ASSIGNMENT_CLARIFY_CANCELLED,
            ASSIGNMENT_COMPLETED,
            ASSIGNMENT_FAILED,
            ASSIGNMENT_CANCELLED,
            ASSIGNMENT_AGENT_FLOW_COMPLETED,
            ASSIGNMENT_AGENT_FLOW_FAILED,
            ASSIGNMENT_AGENT_FLOW_CANCELLED,
            FILE_CHAT_COMPLETED,
            FILE_CHAT_FAILED,
            FILE_CHAT_CANCELLED,
            ASSIGNMENT_CHAT_COMPLETED,
            ASSIGNMENT_CHAT_FAILED,
            ASSIGNMENT_CHAT_CANCELLED,
            MATERIALS_COMPLETED,
            PLAN_TASK_NAME_RESOLVED,
            PLAN_TASK_NAME_FAILED,
            AI_DETECTION_COMPLETED,
            AI_DETECTION_FAILED,
            AI_DETECTION_CANCELLED,
            AI_HUMANIZER_COMPLETED,
            AI_HUMANIZER_FAILED,
            AI_HUMANIZER_CANCELLED);

    public static boolean isTerminal(VerlaAgentEventType type) {
        return TERMINALS.contains(type);
    }
}
