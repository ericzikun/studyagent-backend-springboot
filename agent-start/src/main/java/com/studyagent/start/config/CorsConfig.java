package com.studyagent.start.config;

import com.studyagent.service.config.TaskSubmitConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CORS配置
 */
@Configuration
@EnableConfigurationProperties(TaskSubmitConfig.class)
public class CorsConfig {
    
    @Bean
    public CorsFilter corsFilter(Environment env) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        
        // 允许的源（包括生产域名）
        // studyagent-fronted-v2 本地默认端口为 3001（next dev -p 3001），勿漏否则 /me、Verla 等接口预检失败
        List<String> origins = new ArrayList<>(Arrays.asList(
            "http://localhost:3000",
            "http://127.0.0.1:3000",
            "http://localhost:3001",
            "http://127.0.0.1:3001",
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:8080",
            "https://verla.io",
            "https://www.verla.io",
            "http://101.251.176.55:13000",
            // 测试环境 Next 前端（studyagent-fronted-v2）
            "http://101.251.176.55:13001"
        ));
        String extra = env.getProperty("studyagent.cors.extra-allowed-origins", "");
        if (extra != null && !extra.isBlank()) {
            for (String part : extra.split(",")) {
                String o = part.trim();
                if (!o.isEmpty()) {
                    origins.add(o);
                }
            }
        }
        config.setAllowedOrigins(origins);
        
        // 允许的HTTP方法（含 PATCH：Verla conversation PATCH 等）
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        
        // 允许的请求头
        config.setAllowedHeaders(Arrays.asList("*"));
        
        // 允许携带凭证
        config.setAllowCredentials(true);
        
        // 预检请求的有效期
        config.setMaxAge(3600L);
        
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}

