package com.studyagent.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "notify")
public class NotifyConfig {

    private boolean enabled = false;
    private String apiToken;
    /**
     * 未显式指定 target 时的路由键，需与 dingtalk 配置文件里 {@code targets.<key>} 一致（通常为 default）。
     */
    private String defaultTarget = "default";
    private String defaultEnv = "online";
    private DingTalk dingtalk = new DingTalk();
    private Idempotency idempotency = new Idempotency();
    private RateLimit rateLimit = new RateLimit();

    @Data
    public static class DingTalk {
        private String configFile;
    }

    @Data
    public static class Idempotency {
        private boolean enabled = true;
        private int ttlSeconds = 600;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private int perServicePerMinute = 60;
    }
}
