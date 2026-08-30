package com.studyagent.api.controller.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.demo.LearningCanvasChatRequest;
import com.studyagent.api.dto.demo.LearningCanvasCreateThemeRequest;
import com.studyagent.api.dto.demo.LearningCanvasMasteryRequest;
import com.studyagent.infra.agent.learning.LearningCanvasAgentRuntime;
import com.studyagent.infra.agent.learning.LearningCanvasQuotaRecorder;
import com.studyagent.infra.agent.learning.LearningCanvasStreamEvent;
import com.studyagent.service.application.demo.DemoLearningCanvasService;
import com.studyagent.service.domain.demo.learning.DemoLearningNode;
import com.studyagent.service.domain.demo.learning.DemoLearningTheme;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Learning Canvas Demo 控制器 —— /v1/demo/learning-canvas/*
 * <p>
 * 只做：参数校验 → 取当前用户（clerkUserId）→ 调 Service / infra → 返回 Result / SSE。
 * 不写业务、不打模型。鉴权复用 AuthInterceptor + Clerk。
 */
@Slf4j
@RestController
@RequestMapping("/v1/demo/learning-canvas")
@RequiredArgsConstructor
public class DemoLearningCanvasController {

    private static final long SSE_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    private final DemoLearningCanvasService service;
    private final LearningCanvasAgentRuntime agentRuntime;
    private final LearningCanvasQuotaRecorder quotaRecorder;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "demo-learning-canvas-run");
        thread.setDaemon(true);
        return thread;
    });

    private final java.util.concurrent.ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "demo-learning-canvas-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    // =================================================================
    // 建主题
    // =================================================================

    @PostMapping("/themes")
    public Result<DemoLearningTheme> createTheme(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestBody LearningCanvasCreateThemeRequest req) {
        DemoLearningTheme theme = service.createTheme(clerkUserId, req.getInitialQuery(), req.getPersona());
        return Result.success(theme);
    }

    // =================================================================
    // 历史列表
    // =================================================================

    @GetMapping("/themes")
    public Result<List<DemoLearningTheme>> listThemes(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return Result.success(service.listThemes(clerkUserId, limit));
    }

    // =================================================================
    // 画布快照
    // =================================================================

    @GetMapping("/themes/{themeId}")
    public Result<Map<String, Object>> canvasSnapshot(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long themeId) {
        return Result.success(service.canvasSnapshot(clerkUserId, themeId));
    }

    // =================================================================
    // chat（SSE 流式）
    // =================================================================

    @PostMapping(path = "/themes/{themeId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long themeId,
            @RequestBody LearningCanvasChatRequest req) {
        String message = req.getMessage();
        if (message == null || message.isBlank()) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.PARAM_ERROR, "message is required");
        }
        // 校验归属
        service.prepareChat(clerkUserId, themeId);
        // 纯免费记账
        quotaRecorder.recordFreeUsage(clerkUserId, themeId);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));

        heartbeatScheduler.scheduleAtFixedRate(
                () -> sendComment(emitter, closed),
                HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        executor.submit(() -> {
            try {
                agentRuntime.run(themeId, message, true, event -> {
                    if (closed.get()) {
                        return;
                    }
                    sendEvent(emitter, closed, "message", event);
                    if ("canvas_updated".equals(event.type())) {
                        // 补发完整画布快照（对应 demo sync_canvas）
                        sendSyncCanvas(emitter, closed, clerkUserId, themeId);
                    }
                });
                service.afterChat(themeId);
                sendEvent(emitter, closed, "auto_saved", Map.of("savedAt", java.time.LocalDateTime.now().toString()));
                sendDone(emitter, closed);
            } catch (Exception ex) {
                log.error("[LearningCanvas] chat stream failed: themeId={}", themeId, ex);
                if (!closed.get()) {
                    sendEvent(emitter, closed, "error", Map.of("content", ex.getMessage() == null ? "unknown error" : ex.getMessage()));
                }
            } finally {
                if (!closed.get()) {
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
        return emitter;
    }

    // =================================================================
    // 掌握度校准
    // =================================================================

    @PatchMapping("/nodes/{nodeId}/mastery")
    public Result<DemoLearningNode> calibrateMastery(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long nodeId,
            @RequestBody LearningCanvasMasteryRequest req) {
        return Result.success(service.calibrateMastery(clerkUserId, nodeId, req.getMasteryLevel()));
    }

    // =================================================================
    // SSE 工具
    // =================================================================

    private void sendEvent(SseEmitter emitter, AtomicBoolean closed, String name, Object data) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(name)
                    .data(objectMapper.writeValueAsString(data)));
        } catch (IOException | IllegalStateException ex) {
            closed.set(true);
        }
    }

    private void sendComment(SseEmitter emitter, AtomicBoolean closed) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | IllegalStateException ex) {
            closed.set(true);
        }
    }

    private void sendDone(SseEmitter emitter, AtomicBoolean closed) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data("[DONE]"));
        } catch (IOException | IllegalStateException ex) {
            closed.set(true);
        }
    }

    private void sendSyncCanvas(SseEmitter emitter, AtomicBoolean closed, String clerkUserId, Long themeId) {
        try {
            Map<String, Object> snapshot = service.canvasSnapshot(clerkUserId, themeId);
            sendEvent(emitter, closed, "sync_canvas", Map.of("payload", snapshot));
        } catch (Exception ex) {
            log.warn("[LearningCanvas] sync canvas failed: themeId={}", themeId, ex.getMessage());
        }
    }
}
