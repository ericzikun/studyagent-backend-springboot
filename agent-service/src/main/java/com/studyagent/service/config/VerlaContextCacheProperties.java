package com.studyagent.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "verla.context-cache")
public class VerlaContextCacheProperties {

    private Duration convSummaryTtl = Duration.ofSeconds(60);
    private Duration turnMetaTtl = Duration.ofSeconds(30);
    private Duration sessMetaTtl = Duration.ofSeconds(10);

    private int recentMessageLimit = 20;
    private int traceLimitDefault = 50;
    private int artifactLimitDefault = 80;
    private long maxEntriesPerLayer = 5000L;
}
