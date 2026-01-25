package com.studyagent.api.interceptor;

import com.studyagent.service.domain.user.ClerkClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    
    private final ClerkClient clerkClient;
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 允许 OPTIONS 预检请求（CORS）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        
        // 健康检查接口不需要认证
        if (request.getRequestURI().equals("/health")) {
            return true;
        }
        
        // 获取 Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // 某些接口允许未登录访问（如任务列表、详情）
            String uri = request.getRequestURI();
            if (uri.startsWith("/v1/task/list") || uri.startsWith("/v1/task/detail")) {
                return true;
            }
            
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        
        // 提取 token
        String token = authHeader.substring(7);
        
        try {
            // 验证 token
            ClerkClient.UserInfo userInfo = clerkClient.verifyToken(token);
            
            // 将用户信息存储到 request 属性中
            request.setAttribute("clerkUserId", userInfo.clerkUserId);
            request.setAttribute("userInfo", userInfo);
            
            return true;
        } catch (Exception e) {
            log.warn("Token验证失败: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
    }
}

