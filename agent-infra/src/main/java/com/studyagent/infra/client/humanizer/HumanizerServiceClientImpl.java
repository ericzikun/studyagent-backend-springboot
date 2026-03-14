package com.studyagent.infra.client.humanizer;

import com.studyagent.service.domain.humanizer.HumanizerServiceClient;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient.ChunkInfo;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient.DetectResult;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient.HumanizerResult;
import com.studyagent.service.domain.humanizer.HumanizerServiceClient.SplitSentencesResult;
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
 * 提供三个能力：
 * 1. humanize() - 同步调用 /process（文本改写）
 * 2. detectAI() - 同步调用 /predict（AI 检测，普通 POST）
 * 3. detectAIStream() - 返回 Flux 消费 /predict_stream 的 SSE 流
 */
@Slf4j
@Component
public class HumanizerServiceClientImpl implements HumanizerServiceClient {

    private final WebClient humanizerWebClient;

    @Value("${humanizer-service.url:http://localhost:9000}")
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
     * AI 检测（普通 POST，非 SSE）
     * 调用 Python /predict 端点，返回整体检测结果
     */
    @Override
    public DetectResult detectAI(String text) {
        try {
            log.info("调用 AI 检测 /predict，文本长度: {} 字符", text.length());

            @SuppressWarnings("unchecked")
            Map<String, Object> response = humanizerWebClient.post()
                .uri(humanizerServiceUrl + "/predict")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", text))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null) {
                return DetectResult.builder().code(500).msg("AI detect service returned empty response").build();
            }

            // 解析 Python 响应: {"code": 200, "probability": 0.87, "label": "AI Generated", "elapsed_seconds": 1.23}
            int code = response.get("code") instanceof Number ? ((Number) response.get("code")).intValue() : 500;
            String msg = response.get("msg") != null ? response.get("msg").toString() : null;
            Double probability = response.get("probability") instanceof Number
                ? ((Number) response.get("probability")).doubleValue() : null;
            String label = response.get("label") != null ? response.get("label").toString() : null;
            Double elapsed = response.get("elapsed_seconds") instanceof Number
                ? ((Number) response.get("elapsed_seconds")).doubleValue() : null;

            log.info("AI 检测 /predict 完成: code={}, label={}, prob={}, 耗时={}s", code, label, probability, elapsed);
            return DetectResult.builder()
                .code(code).msg(msg).probability(probability).label(label).elapsedSeconds(elapsed)
                .build();

        } catch (WebClientRequestException e) {
            log.error("AI 检测服务不可达: {}", e.getMessage());
            return DetectResult.builder().code(503).msg("AI detect service unavailable").build();
        } catch (WebClientResponseException e) {
            log.error("AI 检测服务返回错误: status={}", e.getStatusCode());
            return DetectResult.builder().code(e.getStatusCode().value()).msg("AI detect service error: " + e.getMessage()).build();
        } catch (Exception e) {
            log.error("调用 AI 检测 /predict 失败", e);
            return DetectResult.builder().code(500).msg("Failed to call AI detect service: " + e.getMessage()).build();
        }
    }

    /**
     * 文本分句（不做 AI 检测）
     * 调用 Python /split_sentences 端点
     */
    @Override
    public SplitSentencesResult splitSentences(String text) {
        try {
            log.info("调用 /split_sentences，文本长度: {} 字符", text.length());

            @SuppressWarnings("unchecked")
            Map<String, Object> response = humanizerWebClient.post()
                .uri(humanizerServiceUrl + "/split_sentences")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", text))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null) {
                return SplitSentencesResult.builder().code(500).msg("split_sentences returned empty response").build();
            }

            int code = response.get("code") instanceof Number ? ((Number) response.get("code")).intValue() : 500;
            if (code != 200) {
                String msg = response.get("msg") != null ? response.get("msg").toString() : "Unknown error";
                return SplitSentencesResult.builder().code(code).msg(msg).build();
            }

            int totalChunks = response.get("totalChunks") instanceof Number ? ((Number) response.get("totalChunks")).intValue() : 0;
            int totalWords = response.get("totalWords") instanceof Number ? ((Number) response.get("totalWords")).intValue() : 0;

            java.util.List<ChunkInfo> chunks = new java.util.ArrayList<>();
            if (response.get("chunks") instanceof java.util.List<?> rawList) {
                for (Object item : rawList) {
                    if (item instanceof Map<?, ?> m) {
                        chunks.add(ChunkInfo.builder()
                            .index(m.get("index") instanceof Number n ? n.intValue() : 0)
                            .sentence(m.get("sentence") != null ? m.get("sentence").toString() : "")
                            .wordCount(m.get("wordCount") instanceof Number n ? n.intValue() : 0)
                            .build());
                    }
                }
            }

            log.info("/split_sentences 完成: totalChunks={}, totalWords={}", totalChunks, totalWords);
            return SplitSentencesResult.builder()
                .code(200).chunks(chunks).totalChunks(totalChunks).totalWords(totalWords)
                .build();

        } catch (WebClientRequestException e) {
            log.error("split_sentences 服务不可达: {}", e.getMessage());
            return SplitSentencesResult.builder().code(503).msg("Service unavailable").build();
        } catch (Exception e) {
            log.error("调用 /split_sentences 失败", e);
            return SplitSentencesResult.builder().code(500).msg("Failed: " + e.getMessage()).build();
        }
    }

    /**
     * AI 检测 SSE 流式调用
     * 消费 Python /predict_stream 的 SSE 流，返回原始 SSE 事件行
     *
     * @param text 待检测文本
     * @param relaxed 是否使用宽松阈值（用户自己 humanize 过的内容）
     * @return Flux 响应式流，每个元素为一个原始 SSE 数据行
     */
    public Flux<String> detectAIStream(String text, boolean relaxed) {
        log.info("调用 Humanizer /predict_stream SSE，文本长度: {} 字符, relaxed: {}", text.length(), relaxed);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("text", text);
        if (relaxed) {
            body.put("relaxed", true);
        }

        return humanizerWebClient.post()
            .uri(humanizerServiceUrl + "/predict_stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .retrieve()
            .bodyToFlux(String.class)
            .doOnError(WebClientRequestException.class,
                e -> log.error("Humanizer SSE 服务不可达: {}", e.getMessage()))
            .doOnError(e -> !(e instanceof WebClientRequestException),
                e -> log.error("Humanizer SSE 流错误", e));
    }

    /**
     * Humanizer 改写 SSE 流式调用
     * 消费 Python /process_stream 的 SSE 流（estimate → result → done）
     *
     * @param text 待改写文本
     * @return Flux 响应式流
     */
    public Flux<String> humanizeStream(String text) {
        log.info("调用 Humanizer /process_stream SSE，文本长度: {} 字符", text.length());

        return humanizerWebClient.post()
            .uri(humanizerServiceUrl + "/process_stream")
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
