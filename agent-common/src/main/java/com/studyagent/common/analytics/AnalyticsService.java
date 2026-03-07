package com.studyagent.common.analytics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.posthog.java.PostHog;

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

    private PostHog postHog;

    @PostConstruct
    public void init() {
        if (enabled && apiKey != null && !apiKey.isEmpty()) {
            try {
                postHog = new PostHog.Builder(apiKey)
                    .host(host)
                    .build();
                log.info("PostHog 初始化成功, host={}", host);
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
                postHog.shutdown();
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
        if (!enabled || postHog == null) {
            log.debug("PostHog 未启用，跳过事件: {} for user: {}", event, distinctId);
            return;
        }

        try {
            Map<String, Object> props = properties != null ? new HashMap<>(properties) : new HashMap<>();
            postHog.capture(distinctId, event, props);
            log.info("[Analytics] Event: {} | User: {} | Properties: {}", event, distinctId, props);
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
        if (!enabled || postHog == null) {
            log.debug("PostHog 未启用，跳过设置用户属性: {}", distinctId);
            return;
        }

        try {
            postHog.capture(distinctId, "$set", properties);
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
}