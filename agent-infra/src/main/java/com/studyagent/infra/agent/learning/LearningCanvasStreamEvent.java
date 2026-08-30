package com.studyagent.infra.agent.learning;

/**
 * Learning Canvas Agent 流式事件（与 demo SSE 事件类型对齐）
 * <p>
 * type: chunk / tool_start / tool_end / canvas_updated
 * 对应 HTTP 层再包一层 sync_canvas / auto_saved / [DONE]。
 */
public record LearningCanvasStreamEvent(String type, String content, String toolName, Object toolResult) {

    public static LearningCanvasStreamEvent chunk(String content) {
        return new LearningCanvasStreamEvent("chunk", content, null, null);
    }

    public static LearningCanvasStreamEvent toolStart(String toolName) {
        return new LearningCanvasStreamEvent("tool_start", null, toolName, null);
    }

    public static LearningCanvasStreamEvent toolEnd(String toolName, Object result) {
        return new LearningCanvasStreamEvent("tool_end", null, toolName, result);
    }

    public static LearningCanvasStreamEvent canvasUpdated() {
        return new LearningCanvasStreamEvent("canvas_updated", null, null, null);
    }
}
