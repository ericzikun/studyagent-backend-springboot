package com.studyagent.api.config;

import com.studyagent.api.filter.VerlaInternalAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verla 链路 Web 层配置：注册 /v1/internal/verla/* 鉴权 Filter
 * <p>
 * 对应文档 §23.1
 */
@Configuration
public class VerlaWebConfig {

    @Bean
    public VerlaInternalAuthFilter verlaInternalAuthFilter() {
        return new VerlaInternalAuthFilter();
    }

    @Bean
    public FilterRegistrationBean<VerlaInternalAuthFilter> verlaInternalAuthFilterRegistration(
            VerlaInternalAuthFilter filter) {
        FilterRegistrationBean<VerlaInternalAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/v1/internal/verla/*");
        reg.setName("verlaInternalAuthFilter");
        reg.setOrder(0);
        return reg;
    }
}
