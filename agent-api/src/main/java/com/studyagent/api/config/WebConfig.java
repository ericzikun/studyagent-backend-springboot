package com.studyagent.api.config;

import com.studyagent.api.interceptor.AuthInterceptor;
import com.studyagent.api.web.verla.VerlaPublicIdArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

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
                "/api/v1/notify/**",        // Notify API，使用 X-Notify-Token 鉴权
                "/v1/webhook/**",           // Stripe Webhook，使用签名验证
                "/v1/public/email-leads",  // 公开邮箱留资，使用蜜罐 + Redis 限流保护
                "/v1/payment/config",        // Pricing 页面支付配置，允许未登录访问
                "/v1/billing/config",        // V2 商业化套餐配置，允许未登录访问
                "/v1/internal/reports/**",   // 数据报表手动触发，使用 X-Report-Token
                "/v1/internal/**"            // Verla 内部 API（Python -> Java），使用 IP 白名单 + Token + HMAC
            );
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new VerlaPublicIdArgumentResolver());
    }
}
