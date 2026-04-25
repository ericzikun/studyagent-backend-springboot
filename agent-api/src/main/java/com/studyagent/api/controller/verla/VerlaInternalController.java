package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verla 内部接口控制器（Java 暴露给 Py 的反查上下文 API）
 * <p>
 * Day 1 空壳：返回示意结构，让 Py 同学可以并行联调。
 * Day 2 ~ Day 3 由 {@code VerlaContextQueryService} 真正实现。
 * <p>
 * 鉴权由 {@link com.studyagent.api.filter.VerlaInternalAuthFilter} 拦截 {@code /internal/*}。
 * 对应文档 §10 / §21
 */
@Slf4j
@RestController
@RequestMapping("/internal/verla")
public class VerlaInternalController {

    @Value("${verla.internal.mock-data:true}")
    private boolean mockData;

    /**
     * 拉取一个 session 的全量上下文（含同 turn 已完成 session 的 result_json）
     * <p>
     * 真实实现见 §10.1 / §10.2
     */
    @GetMapping("/sessions/{sessionId}/context")
    public Result<Map<String, Object>> getSessionContext(
            @PathVariable Long sessionId,
            @RequestParam(value = "convVersion", required = false) Long convVersion,
            @RequestParam(value = "turnVersion", required = false) Long turnVersion) {
        log.info("[verla-internal] getSessionContext sessionId={}, convVersion={}, turnVersion={}",
                sessionId, convVersion, turnVersion);
        if (!mockData) {
            // TODO: Day 2 PR-10 替换为 VerlaContextQueryService
            return Result.error(501, "not implemented yet");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("convVersion", convVersion == null ? 1 : convVersion);
        data.put("turnVersion", turnVersion == null ? 1 : turnVersion);
        data.put("conversation", Map.of(
                "conversationId", 1001L,
                "primaryIntent", "assignment",
                "workspace", Map.of("lang", "zh-CN")
        ));
        data.put("turn", Map.of(
                "turnId", 55L,
                "userMessage", Map.of(
                        "text", "（mock）帮我做这份英语作业",
                        "attachments", List.of()
                )
        ));
        data.put("upstreamSessions", List.of());
        return Result.success(data);
    }

    /**
     * 拉取最近 N 条 conversation 消息（mock）
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public Result<Map<String, Object>> getRecentMessages(
            @PathVariable Long conversationId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        log.info("[verla-internal] getRecentMessages conversationId={}, limit={}", conversationId, limit);
        Map<String, Object> data = new HashMap<>();
        data.put("conversationId", conversationId);
        data.put("limit", limit);
        data.put("items", List.of());
        return Result.success(data);
    }

    /**
     * 拉取一个 turn 的所有 block 回填（用于 Py 复算）
     */
    @GetMapping("/turns/{turnId}/block-responses")
    public Result<Map<String, Object>> getBlockResponses(@PathVariable Long turnId) {
        log.info("[verla-internal] getBlockResponses turnId={}", turnId);
        Map<String, Object> data = new HashMap<>();
        data.put("turnId", turnId);
        data.put("items", List.of());
        return Result.success(data);
    }
}
