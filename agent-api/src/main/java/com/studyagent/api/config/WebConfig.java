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
                "/v1/internal/reports/**",   // 数据报表手动触发，使用 X-Report-Token
                "/v1/internal/**",           // Verla 内部 API（Python -> Java），使用 IP 白名单 + Token + HMAC
                // 普通附件内容上传在 AuthInterceptor 内按请求类型放行给 upload ticket 鉴权。
                // 普通 finalize 始终用 upload ticket 鉴权。
                "/v1/verla/v2/uploads/*/finalize",
                // 笔记博客音频：audio 标签带不了 Authorization，鉴权用 exp + HMAC 签名。
                "/v1/demo/note/blog-audio/**"
            );
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new VerlaPublicIdArgumentResolver());
    }
}
