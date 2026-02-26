package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.HumanizerRequest;
import com.studyagent.api.dto.response.HumanizerDetectResponse;
import com.studyagent.api.dto.response.HumanizerProcessResponse;
import com.studyagent.api.service.HumanizerApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Humanizer / AI 检测 控制器
 * <p>
 * 提供三个端点：
 * 1. POST /v1/humanizer/detect        — AI 检测（普通 POST）
 * 2. POST /v1/humanizer/detect-stream  — AI 检测 SSE 流式接口
 * 3. POST /v1/humanizer/process        — 文本人性化改写接口
 * <p>
 * 所有请求都经过 AuthInterceptor 认证（Clerk Token）和全局限流。
 */
@Slf4j
@RestController
@RequestMapping("/v1/humanizer")
@RequiredArgsConstructor
public class HumanizerController {

    private final HumanizerApplicationService humanizerApplicationService;

    /**
     * AI 检测（普通 POST，非 SSE）
     * 调用 Python /predict，返回整体检测结果
     */
    @PostMapping("/detect")
    public Result<HumanizerDetectResponse> detect(
            @RequestBody @Valid HumanizerRequest request,
            @RequestAttribute("clerkUserId") String clerkUserId) {
        log.info("AI 检测 POST 请求: userId={}, textLength={}", clerkUserId, request.getText().length());
        humanizerApplicationService.checkDetectStreamLimit();
        HumanizerDetectResponse response = humanizerApplicationService.detectAI(request.getText());
        return Result.success(response);
    }

    /**
     * AI 检测 SSE 流式接口
     * 透传 Python /predict_stream 的 SSE 事件流给前端
     */
    @PostMapping("/detect-stream")
    public SseEmitter detectStream(
            @RequestBody @Valid HumanizerRequest request,
            @RequestAttribute("clerkUserId") String clerkUserId) {
        log.info("AI 检测 SSE 请求: userId={}, textLength={}", clerkUserId, request.getText().length());
        humanizerApplicationService.checkDetectStreamLimit();
        return humanizerApplicationService.detectAIStream(request.getText());
    }

    /**
     * 文本人性化改写接口
     * 同步调用 Python /process，耗时可能数分钟
     */
    @PostMapping("/process")
    public Result<HumanizerProcessResponse> process(
            @RequestBody @Valid HumanizerRequest request,
            @RequestAttribute("clerkUserId") String clerkUserId) {
        log.info("Humanizer 改写请求: userId={}, textLength={}", clerkUserId, request.getText().length());
        humanizerApplicationService.checkProcessLimit();
        HumanizerProcessResponse response = humanizerApplicationService.humanize(request.getText());
        return Result.success(response);
    }

    /**
     * 文本人性化改写 SSE 流式接口
     * 先返回预估时间，再返回改写结果
     */
    @PostMapping("/process-stream")
    public SseEmitter processStream(
            @RequestBody @Valid HumanizerRequest request,
            @RequestAttribute("clerkUserId") String clerkUserId) {
        log.info("Humanizer 改写 SSE 请求: userId={}, textLength={}", clerkUserId, request.getText().length());
        humanizerApplicationService.checkProcessLimit();
        return humanizerApplicationService.humanizeStream(request.getText());
    }
}
