package com.studyagent.common.log.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 日志模块自动配置
 * 会自动扫描并注册日志相关的切面和过滤器
 */
@Configuration
@ComponentScan(basePackages = "com.studyagent.common.log")
public class LogAutoConfiguration {
    // 通过 @ComponentScan 自动注册以下组件：
    // - ApiLogAspect: Controller 层日志切面
    // - ExternalLogAspect: 外部调用日志切面
    // - TraceIdFilter: TraceId 过滤器
}

