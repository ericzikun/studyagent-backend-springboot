package com.studyagent.api.config;

import com.studyagent.api.interceptor.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web配置
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    
    private final AuthInterceptor authInterceptor;
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/**")
            .excludePathPatterns(
                "/health", 
                "/swagger-ui/**", 
                "/v3/api-docs/**",
                "/actuator/**",
                "/api/v1/agent-events/**",  // Python Agent 事件接口，使用 Token 验证
                "/v1/webhook/**"            // Stripe Webhook，使用签名验证
            );
    }
}

