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
     * 未显式指定 target 时的路由键，需与机器人配置文件里 {@code targets.<key>} 一致（通常为 default）。
     */
    private String defaultTarget = "default";
    private String defaultEnv = "online";
    private Robot robot = new Robot();
    /**
     * 内部机器人按业务线选择路由 target；值须与机器人配置文件 {@code targets} 下 key 一致。
     * 默认均为 {@code default}，即与历史单群行为一致。
     */
    private RobotTarget robotTarget = new RobotTarget();
    private Idempotency idempotency = new Idempotency();
    private RateLimit rateLimit = new RateLimit();

    @Data
    public static class RobotTarget {
        /** 作业侧：Stripe 付费/退出付款等 */
        private String assignment = "default";
        /** 用户反馈提交 */
        private String feedback = "default";
        /** 数据日报、周报 */
        private String report = "default";
    }

    @Data
    public static class Robot {
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
