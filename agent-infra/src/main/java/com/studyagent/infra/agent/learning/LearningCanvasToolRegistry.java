package com.studyagent.infra.agent.learning;

import com.studyagent.infra.agent.learning.tools.LearningCanvasTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Learning Canvas 工具注册表。
 * <p>
 * 持有全部 {@link ToolCallback}（工具 schema + 执行），供运行时按策略传给 ChatModel。
 */
@Component
public class LearningCanvasToolRegistry {

    private final LearningCanvasTools tools;

    public LearningCanvasToolRegistry(LearningCanvasTools tools) {
        this.tools = tools;
    }

    /**
     * 全部工具回调（画布就绪后开放）。
     */
    public List<ToolCallback> allCallbacks() {
        return tools.allCallbacks();
    }

    /**
     * 全部工具名。
     */
    public List<String> allToolNames() {
        return tools.allToolNames();
    }

    /**
     * 按工具名过滤回调（用于阶段策略：只给模型部分工具）。
     */
    public List<ToolCallback> callbacksFor(List<String> names) {
        return tools.allCallbacks().stream()
                .filter(c -> names.contains(c.getToolDefinition().name()))
                .toList();
    }
}
