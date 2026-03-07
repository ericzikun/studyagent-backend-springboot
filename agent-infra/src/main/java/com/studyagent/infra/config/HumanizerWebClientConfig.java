package com.studyagent.infra.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Humanizer 服务专用 WebClient 配置
 * <p>
 * 与现有 WebClientConfig 隔离，因为 Humanizer 的 /process 端点
 * 需要 5 分钟超时（多步翻译链耗时长），而现有 WebClient 只有 60 秒超时。
 */
@Configuration
public class HumanizerWebClientConfig {

    // 连接超时：5 秒
    private static final int CONNECT_TIMEOUT_MS = 5000;
    // 读取超时：5 分钟（Humanizer 多步翻译链耗时长）
    private static final long READ_TIMEOUT_MINUTES = 5;
    // 写入超时：30 秒
    private static final int WRITE_TIMEOUT_SECONDS = 30;
    // 响应超时：5 分钟
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * 为 Humanizer 服务创建独立 WebClient 实例
     * 使用 @Qualifier("humanizerWebClient") 注入
     */
    @Bean("humanizerWebClient")
    public WebClient humanizerWebClient() {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
            .responseTimeout(RESPONSE_TIMEOUT)
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(READ_TIMEOUT_MINUTES, TimeUnit.MINUTES))
                .addHandlerLast(new WriteTimeoutHandler(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            );

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
