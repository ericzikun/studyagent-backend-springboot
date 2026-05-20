package com.studyagent.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "verla.context-cache")
public class VerlaContextCacheProperties {

    private boolean redisEnabled = false;
    private String keyPrefix = "verla:v1";

    private Duration convSummaryTtl = Duration.ofSeconds(60);
    private Duration messagesRecentTtl = Duration.ofSeconds(60);
    private Duration messagesHistoryTtl = Duration.ofMinutes(10);
    private Duration turnMetaTtl = Duration.ofSeconds(30);
    private Duration sessMetaTtl = Duration.ofSeconds(10);
    private Duration sessionRunningTtl = Duration.ofSeconds(10);
    private Duration sessionTerminalTtl = Duration.ofMinutes(5);
    private Duration upstreamSessionsTtl = Duration.ofSeconds(30);
    private Duration blockResponsesTtl = Duration.ofSeconds(120);
    private Duration negativeTtl = Duration.ofSeconds(20);
    private Duration redisLockTimeout = Duration.ofSeconds(3);
    private Duration redisLockRetryDelay = Duration.ofMillis(30);
    private int redisLockRetryMaxAttempts = 2;
    private int redisCircuitFailureThreshold = 3;
    private Duration redisCircuitOpenDuration = Duration.ofSeconds(5);
    private double jitterRatio = 0.15d;

    private int recentMessageLimit = 20;
    private int traceLimitDefault = 50;
    private int artifactLimitDefault = 80;
    private long maxEntriesPerLayer = 5000L;
}
