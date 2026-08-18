package com.studyagent.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 公开邮箱留资写入保护参数。
 *
 * <p>只定义当前匿名写接口真实使用的 IP 窗口和每日新增上限，不承载营销订阅配置。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "public-email-lead")
public class PublicEmailLeadProperties {

    private String redisKeyPrefix = "public-email-lead:v1";
    private Duration ipWindow = Duration.ofMinutes(10);
    private int ipMaxRequests = 5;
    private int dailyNewMax = 1000;
}
