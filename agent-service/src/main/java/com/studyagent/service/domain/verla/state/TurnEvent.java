package com.studyagent.service.domain.verla.state;

/**
 * Verla Turn 状态机触发事件
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §11.2 转换表。
 */
public enum TurnEvent {

    /** 用户提交消息（onUserMessage） */
    SUBMIT,
    /** 已有 primaryIntent，跳过 plan */
    SKIP_PLAN,
    /** plan 完成，意图识别成功 */
    PLAN_OK,
    /** plan 完成且无需进入 agent 执行（例如 GENERAL_CHAT） */
    PLAN_COMPLETE,
    /** plan 需要澄清 */
    PLAN_CLARIFY,
    /** plan session 失败 */
    PLAN_FAIL,
    /** 派发 agent session（spawnAgentSession） */
    START_AGENT,
    /** agent 完成 */
    AGENT_OK,
    /** agent 失败 */
    AGENT_FAIL,
    /** 用户主动取消 */
    USER_CANCEL,
    /** 取消已被 Py 确认（agent 真正终止） */
    CANCEL_CONFIRMED,
    /** 看门狗超时 */
    WATCHDOG_TIMEOUT
}
