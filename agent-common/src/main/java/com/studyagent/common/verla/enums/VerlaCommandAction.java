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

    /** 阶段一：需求理解（流式 thinking + 内容总结） */
    CMD_ASSIGNMENT_INIT("cmd.assignment.init"),

    /** 阶段二：需求深入解释 */
    CMD_ASSIGNMENT_DEEP_UNDERSTANDING("cmd.assignment.deep_understanding"),

    /** 阶段三：需求澄清 / 表单构建 */
    CMD_ASSIGNMENT_CLARIFY("cmd.assignment.clarify"),

    /** 启动一次 assignment 正式执行 */
    CMD_ASSIGNMENT_RUN("cmd.assignment.run"),

    /** 取消进行中的 agent 执行 */
    CMD_AGENT_CANCEL("cmd.agent.control.cancel"),

    /** 重试失败的 agent 执行 */
    CMD_AGENT_RETRY("cmd.agent.control.retry"),

    /** V2: 用户提交澄清问卷的响应 */
    CMD_CLARIFY_SUBMIT("cmd.clarify.submit"),

    /** V2: 触发附件解析（finalize 上传时下发） */
    CMD_ATTACHMENT_PARSE("cmd.attachment.parse"),

    /** V2: 学习材料生成（Flashcard / Quiz / Study Guide 等） */
    CMD_MATERIALS_GENERATE("cmd.materials.generate"),

    /** V2: AI 检测（Python humanizer_engine / 远程 Flask） */
    CMD_DETECTION_RUN("cmd.detection.run"),

    /** V2: 文本 Humanizer 改写 */
    CMD_HUMANIZER_RUN("cmd.humanizer.run"),

    /** V2: slides source artifact -> editor json seed */
    CMD_SLIDES_CONVERT_TO_EDITOR_JSON("cmd.slides.convert_to_editor_json");

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
