package com.studyagent.infra.agent.learning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.time.Duration;

/**
 * Learning Canvas ChatModel 手动创建（懒加载）。
 * <p>
 * 背景：Spring AI 的 OpenAiChatAutoConfiguration 在 OPENAI_API_KEY 缺失时启动即失败
 * （docker 部署环境未注入 key 会直接崩掉整个应用）。本 Demo 只需 ChatModel，改为手动
 * 懒加载创建：key 缺失时返回 null（应用正常启动，仅调用 Learning Canvas 时报清晰错误），
 * 与 demo 的延迟初始化思路一致。
 * <p>
 * 配置源：application.yml 的 spring.ai.openai.*（与自动配置同一套前缀，仅关闭了自动装配）。
 */
@Configuration
public class LearningCanvasAiConfig {

    private static final Logger log = LoggerFactory.getLogger(LearningCanvasAiConfig.class);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Double temperature;
    private final Integer maxTokens;
    private final String completionsPath;
    private final String proxyHost;
    private final Integer proxyPort;
    private final Integer timeoutMs;

    public LearningCanvasAiConfig(
            @Value("${spring.ai.openai.api-key:${OPENAI_API_KEY:}}") String apiKey,
            @Value("${spring.ai.openai.base-url:${OPENAI_BASE_URL:https://aiberm.com/v1}}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:${OPENAI_MODEL:google/gemini-2.5-pro}}") String model,
            @Value("${spring.ai.openai.chat.options.temperature:${OPENAI_TEMPERATURE:0.4}}") Double temperature,
            @Value("${spring.ai.openai.chat.options.max-tokens:${OPENAI_MAX_TOKENS:16000}}") Integer maxTokens,
            @Value("${spring.ai.openai.chat.completions-path:${OPENAI_COMPLETIONS_PATH:/v1/chat/completions}}") String completionsPath,
            @Value("${OPENAI_PROXY_HOST:}") String proxyHost,
            @Value("${OPENAI_PROXY_PORT:}") Integer proxyPort,
            @Value("${OPENAI_TIMEOUT_MS:120000}") Integer timeoutMs) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://aiberm.com/v1" : baseUrl.trim();
        this.model = model == null || model.isBlank() ? "google/gemini-2.5-pro" : model.trim();
        this.temperature = temperature == null ? 0.4 : temperature;
        this.maxTokens = maxTokens == null ? 16000 : maxTokens;
        String normalizedCompletionsPath = completionsPath == null || completionsPath.isBlank()
                ? "/v1/chat/completions" : completionsPath.trim();
        // URL 规范化：OpenAiApi 拼接规则是 baseUrl + completionsPath。
        // 若 baseUrl 已带 /v1（如 https://aiberm.com/v1）而 completionsPath 以 /v1 开头，
        // 会拼出 /v1/v1/chat/completions 双段错误。此处去重。
        if (this.baseUrl.endsWith("/v1") && normalizedCompletionsPath.startsWith("/v1/")) {
            normalizedCompletionsPath = normalizedCompletionsPath.substring("/v1".length());
            log.info("[LearningCanvas] normalized completionsPath to {} (baseUrl ends with /v1)", normalizedCompletionsPath);
        }
        this.completionsPath = normalizedCompletionsPath;
        this.proxyHost = proxyHost == null ? "" : proxyHost.trim();
        this.proxyPort = proxyPort == null || proxyPort <= 0 ? null : proxyPort;
        this.timeoutMs = timeoutMs == null || timeoutMs <= 0 ? 120000 : timeoutMs;
    }

    /**
     * 懒加载 ChatModel：key 未配置时返回 null，不阻断应用启动。
     */
    @Bean
    @Lazy
    public ChatModel learningCanvasChatModel() {
        if (apiKey.isEmpty()) {
            log.warn("[LearningCanvas] OPENAI_API_KEY / spring.ai.openai.api-key 未配置：ChatModel 未创建，"
                    + "Learning Canvas 调用将报错（其它功能不受影响）。");
            return null;
        }
        log.info("[LearningCanvas] creating ChatModel: baseUrl={}, model={}, temperature={}, maxTokens={}, timeoutMs={}{}",
                baseUrl, model, temperature, maxTokens, timeoutMs,
                proxyHost.isBlank() ? "" : ", proxy=" + proxyHost + ":" + proxyPort);
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath(completionsPath);
        if (!proxyHost.isBlank() && proxyPort != null) {
            // OpenAiApi 的 chat 调用走 RestClient；代理要配在 RestClient 的
            // ClientHttpRequestFactory 上（Reactor Netty，与 WebClient 同底层）。
            HttpClient httpClient = HttpClient.create()
                    .proxy(proxy -> proxy.type(ProxyProvider.Proxy.HTTP)
                            .host(proxyHost)
                            .port(proxyPort))
                    .responseTimeout(Duration.ofMillis(timeoutMs));
            RestClient.Builder restClientBuilder = RestClient.builder()
                    .requestFactory(new ReactorClientHttpRequestFactory(httpClient));
            apiBuilder.restClientBuilder(restClientBuilder);
            log.info("[LearningCanvas] ChatModel 走代理: {}:{}", proxyHost, proxyPort);
        }
        OpenAiApi api = apiBuilder.build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }
}
