package com.studyagent.common.verla.enums;

/**
 * Tool 调用状态机（最简版）。
 * <p>
 * 由 Py 维护并通过 {@code AGENT_TOOL_CALL_RECORDED} 事件推送，Java 仅做存档/回放。
 * 对应文档 docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3 / §4。
 */
public enum VerlaToolStatus {

    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public static VerlaToolStatus fromCode(String code) {
        if (code == null) {
            return PENDING;
        }
        for (VerlaToolStatus s : values()) {
            if (s.name().equalsIgnoreCase(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown tool status: " + code);
    }
}
