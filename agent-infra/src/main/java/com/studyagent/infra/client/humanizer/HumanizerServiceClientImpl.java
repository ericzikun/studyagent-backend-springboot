package com.studyagent.infra.client.humanizer;

import com.studyagent.service.domain.humanizer.HumanizerServiceClient;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient.HumanizerResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Humanizer 服务客户端实现
 * <p>
 * 通过 WebClient 调用 Python Flask 服务（端口 9000）。
 * 提供两个能力：
 * 1. humanize() - 同步调用 /process（文本改写）
 * 2. detectAIStream() - 返回 Flux 消费 /predict_stream 的 SSE 流
 */
@Slf4j
@Component
public class HumanizerServiceClientImpl implements HumanizerServiceClient {

    private final WebClient humanizerWebClient;

    @Value("${humanizer-service.url:http://47.88.58.79:9000}")
    private String humanizerServiceUrl;

    public HumanizerServiceClientImpl(@Qualifier("humanizerWebClient") WebClient humanizerWebClient) {
        this.humanizerWebClient = humanizerWebClient;
    }

    /**
     * 文本人性化改写（同步调用）
     * 调用 Python /process 端点，耗时可能数分钟
     */
    @Override
    public HumanizerResult humanize(String text) {
        try {
            log.info("调用 Humanizer /process，文本长度: {} 字符", text.length());

            @SuppressWarnings("unchecked")
            Map<String, Object> response = humanizerWebClient.post()
                .uri(humanizerServiceUrl + "/process")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", text))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null) {
                log.warn("Humanizer /process 返回 null");
                return HumanizerResult.builder()
                    .code(500)
                    .msg("Humanizer service returned empty response")
                    .build();
            }

            // 解析 Python 响应: {"code": 200, "msg": "Success", "data": {"result": "..."}, "elapsed_seconds": 45.2}
            int code = response.get("code") instanceof Number ? ((Number) response.get("code")).intValue() : 500;
            String msg = response.get("msg") != null ? response.get("msg").toString() : "";
            Double elapsed = response.get("elapsed_seconds") instanceof Number
                ? ((Number) response.get("elapsed_seconds")).doubleValue() : null;

            String result = null;
            if (response.get("data") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                result = data.get("result") != null ? data.get("result").toString() : null;
            }

            log.info("Humanizer /process 完成: code={}, 耗时={}s", code, elapsed);
            return HumanizerResult.builder()
                .code(code)
                .msg(msg)
                .result(result)
                .elapsedSeconds(elapsed)
                .build();

        } catch (WebClientRequestException e) {
            log.error("Humanizer 服务不可达: {}", e.getMessage());
            return HumanizerResult.builder()
                .code(503)
                .msg("Humanizer service unavailable")
                .build();
        } catch (WebClientResponseException e) {
            log.error("Humanizer 服务返回错误: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return HumanizerResult.builder()
                .code(e.getStatusCode().value())
                .msg("Humanizer service error: " + e.getMessage())
                .build();
        } catch (Exception e) {
            log.error("调用 Humanizer /process 失败", e);
            return HumanizerResult.builder()
                .code(500)
                .msg("Failed to call Humanizer service: " + e.getMessage())
                .build();
        }
    }

    /**
     * AI 检测 SSE 流式调用
     * 消费 Python /predict_stream 的 SSE 流，返回原始 SSE 事件行
     * <p>
     * 注意：此方法不在领域接口中定义（避免领域层依赖 reactor），
     * 由 HumanizerApplicationService 直接调用。
     *
     * @param text 待检测文本
     * @return Flux 响应式流，每个元素为一个原始 SSE 数据行
     */
    public Flux<String> detectAIStream(String text) {
        log.info("调用 Humanizer /predict_stream SSE，文本长度: {} 字符", text.length());

        return humanizerWebClient.post()
            .uri(humanizerServiceUrl + "/predict_stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("text", text))
            .accept(MediaType.TEXT_EVENT_STREAM)
            .retrieve()
            .bodyToFlux(String.class)
            .doOnError(WebClientRequestException.class,
                e -> log.error("Humanizer SSE 服务不可达: {}", e.getMessage()))
            .doOnError(e -> !(e instanceof WebClientRequestException),
                e -> log.error("Humanizer SSE 流错误", e));
    }
}
