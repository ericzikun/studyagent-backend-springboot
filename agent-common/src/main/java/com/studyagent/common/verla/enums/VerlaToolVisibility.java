package com.studyagent.common.verla.enums;

/**
 * Tool 调用对前端的可见性。
 * <p>
 * 对应文档 docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3 / §4。
 *
 * <ul>
 *   <li>{@link #INTERNAL}：仅写入 trace（{@code verla_tool_calls}），
 *       不会作为聊天 message 暴露给前端 chat 流；右栏 Trace 视图可见。</li>
 *   <li>{@link #USER_VISIBLE}：除 trace 外，还会作为 assistant message block
 *       推给前端聊天历史（例如 web_search 引用列表）。</li>
 * </ul>
 */
public enum VerlaToolVisibility {

    INTERNAL,
    USER_VISIBLE;

    public static VerlaToolVisibility fromCode(String code) {
        if (code == null) {
            return INTERNAL;
        }
        for (VerlaToolVisibility v : values()) {
            if (v.name().equalsIgnoreCase(code)) {
                return v;
            }
        }
        return INTERNAL;
    }
}
