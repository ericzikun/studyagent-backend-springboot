package com.studyagent.common.analytics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.posthog.server.PostHog;
import com.posthog.server.PostHogCaptureOptions;
import com.posthog.server.PostHogConfig;
import com.posthog.server.PostHogInterface;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;

/**
 * PostHog 分析服务
 * 用于后端埋点，支持用户行为追踪
 */
@Slf4j
@Service
public class AnalyticsService {

    @Value("${posthog.api-key:}")
    private String apiKey;

    @Value("${posthog.host:https://app.posthog.com}")
    private String host;

    @Value("${posthog.enabled:false}")
    private boolean enabled;

    @Value("${posthog.environment:}")
    private String environment;

    @Value("${posthog.app-version:}")
    private String appVersion;

    @Value("${posthog.debug:false}")
    private boolean debug;

    @Value("${posthog.flush-at:20}")
    private int flushAt;

    @Value("${posthog.flush-interval-seconds:5}")
    private int flushIntervalSeconds;

    private PostHogInterface postHog;

    @PostConstruct
    public void init() {
        if (enabled && apiKey != null && !apiKey.isBlank()) {
            try {
                PostHogConfig config = PostHogConfig.builder(apiKey)
                        .host(host)
                        .debug(debug)
                        .preloadFeatureFlags(false)
                        .flushAt(flushAt)
                        .flushIntervalSeconds(flushIntervalSeconds)
                        .build();
                postHog = PostHog.with(config);
                log.info("PostHog 初始化成功, host={}, flushAt={}, flushIntervalSeconds={}, debug={}",
                        host, flushAt, flushIntervalSeconds, debug);
            } catch (Exception e) {
                log.error("PostHog 初始化失败: {}", e.getMessage());
            }
        } else {
            log.info("PostHog 未启用或未配置 API Key");
        }
    }

    @PreDestroy
    public void destroy() {
        if (postHog != null) {
            try {
                postHog.flush();
                postHog.close();
                log.info("PostHog 连接已关闭");
            } catch (Exception e) {
                log.warn("PostHog 关闭时出错: {}", e.getMessage());
            }
        }
    }

    /**
     * 捕获事件
     *
     * @param distinctId 用户唯一标识（如 clerkUserId）
     * @param event      事件名称
     * @param properties 事件属性
     */
    public void capture(String distinctId, String event, Map<String, Object> properties) {
        if (distinctId == null || distinctId.isBlank()) {
            log.warn("PostHog 跳过事件，distinctId 为空: {}", event);
            return;
        }
        if (!enabled || postHog == null) {
            log.debug("PostHog 未启用，跳过事件: {} for user: {}", event, distinctId);
            return;
        }

        try {
            Map<String, Object> props = properties != null ? new HashMap<>(properties) : new HashMap<>();
            applyDefaultEventProperties(props);
            PostHogCaptureOptions options = PostHogCaptureOptions.builder()
                    .properties(props)
                    .build();
            postHog.capture(distinctId, event, options);
            log.info("[Analytics] Event queued: {} | User: {} | Properties: {}", event, distinctId, props);
        } catch (Exception e) {
            log.error("[Analytics] 发送事件失败: {} - {}", event, e.getMessage());
        }
    }

    /**
     * 捕获事件（无属性）
     */
    public void capture(String distinctId, String event) {
        capture(distinctId, event, null);
    }

    /**
     * 设置用户属性
     *
     * @param distinctId 用户唯一标识
     * @param properties 用户属性
     */
    public void setUserProperties(String distinctId, Map<String, Object> properties) {
        if (distinctId == null || distinctId.isBlank()) {
            log.warn("PostHog 跳过用户属性，distinctId 为空");
            return;
        }
        if (!enabled || postHog == null) {
            log.debug("PostHog 未启用，跳过设置用户属性: {}", distinctId);
            return;
        }

        try {
            postHog.identify(distinctId, properties);
            log.info("[Analytics] Set user properties for: {} | Properties: {}", distinctId, properties);
        } catch (Exception e) {
            log.error("[Analytics] 设置用户属性失败: {}", e.getMessage());
        }
    }

    /**
     * 识别用户（别名）
     */
    public void identify(String distinctId, Map<String, Object> properties) {
        setUserProperties(distinctId, properties);
    }

    /**
     * 判断是否启用
     */
    public boolean isEnabled() {
        return enabled && postHog != null;
    }

    private void applyDefaultEventProperties(Map<String, Object> props) {
        props.put("event_source", "backend");
        props.put("event_version", "v2");
        if (environment != null && !environment.isBlank()) {
            props.put("environment", environment.trim());
        }
        if (appVersion != null && !appVersion.isBlank()) {
            props.put("app_version", appVersion.trim());
        }
    }
}
