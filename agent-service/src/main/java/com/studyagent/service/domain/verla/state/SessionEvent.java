package com.studyagent.service.domain.verla.state;

/**
 * Verla Session 状态机触发事件
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §11.3 转换表。
 */
public enum SessionEvent {

    /** 派发命令（写 outbox 后立刻转入 DISPATCHING） */
    DISPATCH,
    /** broker 确认接收 */
    ACK_OK,
    /** broker NACK / Returns */
    ACK_FAIL,
    /** 收到 AGENT_STARTED */
    AGENT_STARTED,
    /** 收到 STREAM_CHUNK / STEP_* / PROGRESS（自循环） */
    STREAM_TICK,
    /** 收到 AGENT_COMPLETED */
    AGENT_COMPLETED,
    /** 收到 AGENT_FAILED */
    AGENT_FAILED,
    /** 收到 AGENT_CANCELLED */
    AGENT_CANCELLED,
    /** 用户主动取消（先发 cmd.agent.cancel，等回执） */
    USER_CANCEL,
    /** 看门狗超时 */
    WATCHDOG_TIMEOUT
}
