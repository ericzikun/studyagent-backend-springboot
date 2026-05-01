package com.studyagent.common.verla.enums;

import lombok.Getter;

/**
 * Verla Java→Py 命令动作枚举
 * <p>
 * 对应文档 docs/verla-Java侧MVP技术方案.md §6.3
 */
@Getter
public enum VerlaCommandAction {

    /** 意图识别 */
    CMD_PLAN_INTENT("cmd.plan.intent"),

    /** 启动一次 agent 执行（如作业 / materials 等功能） */
    CMD_AGENT_RUN("cmd.agent.run"),

    /** 取消进行中的 agent 执行 */
    CMD_AGENT_CANCEL("cmd.agent.control.cancel"),

    /** 重试失败的 agent 执行 */
    CMD_AGENT_RETRY("cmd.agent.control.retry"),

    /** V2: 用户提交澄清问卷的响应 */
    CMD_CLARIFY_SUBMIT("cmd.clarify.submit"),

    /** V2: 触发附件解析（finalize 上传时下发） */
    CMD_ATTACHMENT_PARSE("cmd.attachment.parse");

    private final String code;

    VerlaCommandAction(String code) {
        this.code = code;
    }

    public static VerlaCommandAction fromCode(String code) {
        for (VerlaCommandAction a : values()) {
            if (a.code.equals(code)) {
                return a;
            }
        }
        throw new IllegalArgumentException("unknown verla command action: " + code);
    }
}
