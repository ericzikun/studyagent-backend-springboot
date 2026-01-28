package com.studyagent.api.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.domain.user.ClerkClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证拦截器
 * 
 * 增强版：提供详细的错误信息，帮助前端做出正确的响应
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    
    private final ClerkClient clerkClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
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
            // 任务列表和详情接口需要登录，不允许未登录访问
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, 
                "MISSING_TOKEN", "Authorization header is missing or invalid");
            return false;
        }
        
        // 提取 token
        String token = authHeader.substring(7);
        
        // 检查 token 是否为空
        if (token.isEmpty()) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, 
                "EMPTY_TOKEN", "Token is empty");
            return false;
        }
        
        try {
            // 验证 token
            ClerkClient.UserInfo userInfo = clerkClient.verifyToken(token);
            
            if (userInfo == null) {
                // token 验证失败（可能是过期或无效）
                sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, 
                    "TOKEN_EXPIRED", "Token is expired or invalid, please re-login");
                return false;
            }
            
            // 将用户信息存储到 request 属性中
            request.setAttribute("clerkUserId", userInfo.clerkUserId);
            request.setAttribute("userInfo", userInfo);
            
            return true;
        } catch (RuntimeException e) {
            String errorMessage = e.getMessage();
            String errorCode = "TOKEN_INVALID";
            
            // 根据错误类型提供更精确的错误码
            if (errorMessage != null) {
                if (errorMessage.contains("expired")) {
                    errorCode = "TOKEN_EXPIRED";
                } else if (errorMessage.contains("JWT parsing failed")) {
                    errorCode = "TOKEN_MALFORMED";
                }
            }
            
            log.warn("Token验证失败: {} (错误码: {})", errorMessage, errorCode);
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, 
                errorCode, errorMessage != null ? errorMessage : "Token validation failed");
            return false;
        } catch (Exception e) {
            log.error("Token验证异常: {}", e.getMessage(), e);
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "AUTH_ERROR", "Authentication service error");
            return false;
        }
    }
    
    /**
     * 发送 JSON 格式的错误响应
     */
    private void sendErrorResponse(HttpServletResponse response, int status, String errorCode, String message) {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        
        Map<String, Object> errorBody = new HashMap<>();
        errorBody.put("meta", Map.of(
            "status_code", status == 401 ? 401 : 500,
            "status_msg", message
        ));
        errorBody.put("data", Map.of(
            "error_code", errorCode,
            "error_message", message
        ));
        
        try {
            response.getWriter().write(objectMapper.writeValueAsString(errorBody));
        } catch (IOException e) {
            log.error("Failed to write error response", e);
        }
    }
}

