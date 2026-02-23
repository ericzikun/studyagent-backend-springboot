package com.studyagent.api.service;

import com.studyagent.api.dto.response.HumanizerProcessResponse;
import com.studyagent.common.exception.RateLimitExceededException;
import com.studyagent.infra.client.humanizer.HumanizerServiceClientImpl;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient.HumanizerResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Humanizer 应用服务
 * <p>
 * 负责：
 * 1. SSE 流式 AI 检测：消费 Python SSE 流，通过 SseEmitter 透传给前端
 * 2. 文本人性化改写：调用领域接口，转换为响应 DTO
 * 3. 全局限流：基于滑动窗口算法保护 Python 服务
 */
@Slf4j
@Service
public class HumanizerApplicationService {

    private final HumanizerServiceClient humanizerServiceClient;
    private final HumanizerServiceClientImpl humanizerServiceClientImpl;

    // ========== 限流相关 ==========
    private static final long WINDOW_MS = 60_000L;
    private final ConcurrentLinkedDeque<Long> detectTimestamps = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Long> processTimestamps = new ConcurrentLinkedDeque<>();

    @Value("${humanizer-service.rate-limit.detect-stream:10}")
    private int detectStreamLimit;

    @Value("${humanizer-service.rate-limit.process:5}")
    private int processLimit;

    public HumanizerApplicationService(HumanizerServiceClient humanizerServiceClient,
                                       HumanizerServiceClientImpl humanizerServiceClientImpl) {
        this.humanizerServiceClient = humanizerServiceClient;
        this.humanizerServiceClientImpl = humanizerServiceClientImpl;
    }

    /** 检查 AI 检测 SSE 限流 */
    public void checkDetectStreamLimit() {
        checkLimit(detectTimestamps, detectStreamLimit, "AI Detect Stream");
    }

    /** 检查 Humanizer 改写限流 */
    public void checkProcessLimit() {
        checkLimit(processTimestamps, processLimit, "Humanizer Process");
    }

    private void checkLimit(ConcurrentLinkedDeque<Long> timestamps, int maxRequests, String endpoint) {
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS;
        while (!timestamps.isEmpty() && timestamps.peekFirst() != null && timestamps.peekFirst() < windowStart) {
            timestamps.pollFirst();
        }
        if (timestamps.size() >= maxRequests) {
            log.warn("限流触发: endpoint={}, 当前窗口请求数={}, 上限={}", endpoint, timestamps.size(), maxRequests);
            throw new RateLimitExceededException(endpoint);
        }
        timestamps.addLast(now);
    }

    /**
     * AI 检测 SSE 流式接口
     * <p>
     * 消费 Python /predict_stream 的 SSE 流，解析事件名和数据，
     * 通过 SseEmitter 重新发射给前端。
     *
     * @param text 待检测文本
     * @return SseEmitter 用于 SSE 响应
     */
    public SseEmitter detectAIStream(String text) {
        // 5 分钟超时，足够处理长文本
        SseEmitter emitter = new SseEmitter(300_000L);

        // 订阅 Python SSE 流
        Disposable subscription = humanizerServiceClientImpl.detectAIStream(text)
            .subscribe(
                rawLine -> {
                    try {
                        // WebClient bodyToFlux(String.class) 对 SSE 流会返回 data 行的内容
                        // 直接作为 data 发送，前端通过 EventSource 接收
                        emitter.send(SseEmitter.event().data(rawLine));
                    } catch (IOException e) {
                        log.warn("SSE 发送失败（前端可能已断开）: {}", e.getMessage());
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    log.error("SSE 流错误", error);
                    try {
                        // 发送错误事件给前端
                        emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"msg\":\"" + error.getMessage().replace("\"", "'") + "\"}"));
                    } catch (IOException ignored) {
                        // 前端已断开，忽略
                    }
                    emitter.completeWithError(error);
                },
                () -> {
                    log.info("SSE 流完成");
                    emitter.complete();
                }
            );

        // 前端断开时取消上游订阅，释放资源
        emitter.onCompletion(() -> {
            if (!subscription.isDisposed()) {
                subscription.dispose();
            }
        });
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时");
            if (!subscription.isDisposed()) {
                subscription.dispose();
            }
        });
        emitter.onError(e -> {
            if (!subscription.isDisposed()) {
                subscription.dispose();
            }
        });

        return emitter;
    }

    /**
     * 文本人性化改写
     *
     * @param text 待改写文本
     * @return 改写响应
     */
    public HumanizerProcessResponse humanize(String text) {
        HumanizerResult result = humanizerServiceClient.humanize(text);

        if (result.getCode() != 200) {
            throw new RuntimeException(result.getMsg() != null ? result.getMsg() : "Humanizer service error");
        }

        return HumanizerProcessResponse.builder()
            .result(result.getResult())
            .elapsedSeconds(result.getElapsedSeconds())
            .build();
    }
}
