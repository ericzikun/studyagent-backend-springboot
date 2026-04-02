package com.studyagent.infra.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient配置
 * 增强版：包含超时设置、重试机制、错误处理
 */
@Slf4j
@Configuration
public class WebClientConfig {
    
    // 连接超时：5秒
    private static final int CONNECT_TIMEOUT_MS = 5000;
    // 读取超时：60秒（追问接口调用 LLM 需要较长时间）
    private static final int READ_TIMEOUT_SECONDS = 90;
    // 写入超时：30秒
    private static final int WRITE_TIMEOUT_SECONDS = 30;
    // 响应超时：60秒（追问接口调用 LLM 需要较长时间）
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(90);
    
    @Bean
    public WebClient webClient() {
        // 配置底层 HttpClient 的超时
        HttpClient httpClient = HttpClient.create()
            // 连接超时
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
            // 响应超时
            .responseTimeout(RESPONSE_TIMEOUT)
            // 配置读写超时处理器
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .addHandlerLast(new WriteTimeoutHandler(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            );
        
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            // 添加请求日志过滤器
            .filter(logRequest())
            // 添加响应日志过滤器
            .filter(logResponse())
            .build();
    }
    
    /**
     * 请求日志过滤器
     */
    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            log.debug("WebClient 请求: {} {}", clientRequest.method(), clientRequest.url());
            return Mono.just(clientRequest);
        });
    }
    
    /**
     * 响应日志过滤器
     */
    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            log.debug("WebClient 响应: {} {}", clientResponse.statusCode(), clientResponse.headers().asHttpHeaders());
            return Mono.just(clientResponse);
        });
    }
}

