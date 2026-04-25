package com.studyagent.api.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verla 指标 fallback 配置：
 * <p>
 * 现网未引入 spring-boot-starter-actuator，缺省没有 {@link MeterRegistry} bean。
 * Verla 多个组件（VerlaContextQueryService 等）依赖 MeterRegistry 计数。
 * 这里在没有任何 MeterRegistry 时挂一个 SimpleMeterRegistry，
 * 等 Day 7 接入 Prometheus 时再切到 PrometheusMeterRegistry。
 */
@Configuration
public class VerlaMetricsConfig {

    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    public MeterRegistry verlaSimpleMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}
