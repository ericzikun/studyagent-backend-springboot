package com.studyagent.api.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.log.util.TraceIdUtil;
import com.studyagent.service.domain.user.ClerkClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP 认证拦截器。
 *
 * <p>本类只负责从 Authorization header 或受限的 Verla SSE 查询参数提取 Token，
 * 所有 Clerk JWT 的密码学验证统一委托给 {@link ClerkClient#verifyToken(String)}。
 * 本类不解析 JWT，也不提供验签失败后的降级路径。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    
    private final ClerkClient clerkClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 本地开发用 dev-bypass：跳过 Clerk 校验，从 header X-Dev-User 或 query dev_user 取 userId。
     * <ul>
     *   <li>默认关闭（生产/Docker baseline 不会启用）；</li>
     *   <li>仅 application-local.yml / application-dev.yml 显式置为 true；</li>
     *   <li>开启后日志会以 WARN 提示，避免误生产配置。</li>
     * </ul>
     * 详见 docs/verla-端到端调用链路-做作业示例.md §四。
     */
    @Value("${auth.dev-bypass.enabled:false}")
    private boolean devBypassEnabled;

    /** 当 dev-bypass 开启但调用方完全没传 dev_user/X-Dev-User 时使用的兜底用户 */
    @Value("${auth.dev-bypass.default-user:dev-user-001}")
    private String devBypassDefaultUser;


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 允许 OPTIONS 预检请求（CORS）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // Internal APIs are authenticated by dedicated servlet filters such as
        // VerlaInternalAuthFilter, not by Clerk bearer tokens.
        if (isInternalRequest(request)) {
            return true;
        }
        
        // 健康检查接口不需要认证
        if (request.getRequestURI().equals("/health")) {
            return true;
        }
        
        // 获取 Authorization header
        String authHeader = request.getHeader("Authorization");
        // SSE 兼容：EventSource 不支持自定义 header，仅 verla SSE 路径允许从 query string 取 access_token
        // 详见 docs/verla-Java侧MVP技术方案.md §13.1
        if ((authHeader == null || !authHeader.startsWith("Bearer ")) && isVerlaSseRequest(request)) {
            String qsToken = request.getParameter("access_token");
            if (qsToken != null && !qsToken.isEmpty()) {
                authHeader = "Bearer " + qsToken;
            }
        }

        // 本地开发兜底：dev-bypass 仅在 application-{local,dev}.yml 显式打开时生效。
        if ((authHeader == null || !authHeader.startsWith("Bearer ")) && devBypassEnabled) {
            String devUser = request.getHeader("X-Dev-User");
            if (devUser == null || devUser.isBlank()) {
                devUser = request.getParameter("dev_user");
            }
            if (devUser == null || devUser.isBlank()) {
                devUser = devBypassDefaultUser;
            }
            log.warn("[AuthInterceptor] dev-bypass active, clerkUserId={} for {} {} (do NOT enable in prod)",
                    devUser, request.getMethod(), request.getRequestURI());
            ClerkClient.UserInfo info = new ClerkClient.UserInfo();
            info.clerkUserId = devUser;
            info.displayName = devUser;
            info.email = devUser + "@local.dev";
            request.setAttribute("clerkUserId", devUser);
            request.setAttribute("userInfo", info);
            TraceIdUtil.setUserId(devUser);
            return true;
        }

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
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
            // Header 和 SSE query token 必须通过同一个签名验证边界。
            ClerkClient.UserInfo userInfo = clerkClient.verifyToken(token);
            
            if (userInfo == null) {
                sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, 
                    "TOKEN_EXPIRED", "Token is expired or invalid, please re-login");
                return false;
            }
            
            // 验证 clerkUserId 不为空（增强的防御性检查）
            if (userInfo.clerkUserId == null || userInfo.clerkUserId.isEmpty()) {
                log.error("[AuthInterceptor] Token 验证成功但 clerkUserId 为空！");
                sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, 
                    "USER_ID_MISSING", "Failed to extract user ID from token");
                return false;
            }
            
            // 将用户信息存储到 request 属性中
            request.setAttribute("clerkUserId", userInfo.clerkUserId);
            request.setAttribute("userInfo", userInfo);
            // 注入 userId 到 MDC，供日志框架自动携带（配合 TraceIdFilter 清理）
            TraceIdUtil.setUserId(userInfo.clerkUserId);

            log.debug("[AuthInterceptor] 用户 {} 请求 {} {}",
                userInfo.clerkUserId, request.getMethod(), request.getRequestURI());
            
            return true;
        } catch (IllegalStateException e) {
            log.error("[AuthInterceptor] Clerk 验签服务不可用, URI: {}", request.getRequestURI(), e);
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "AUTH_SERVICE_UNAVAILABLE", "Authentication service is unavailable");
            return false;
        } catch (RuntimeException e) {
            String errorMessage = e.getMessage();
            String errorCode = mapTokenErrorCode(errorMessage);
            
            log.warn("[AuthInterceptor] Token验证失败: {} (错误码: {}), URI: {}", 
                errorMessage, errorCode, request.getRequestURI());
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, 
                errorCode, "Token is invalid or expired");
            return false;
        } catch (Exception e) {
            log.error("[AuthInterceptor] Token验证异常: {}, URI: {}", 
                e.getMessage(), request.getRequestURI(), e);
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "AUTH_ERROR", "Authentication service error");
            return false;
        }
    }
    
    private String mapTokenErrorCode(String errorMessage) {
        String normalized = errorMessage == null ? "" : errorMessage.toUpperCase(Locale.ROOT);
        if (normalized.contains("TOKEN_EXPIRED")) {
            return "TOKEN_EXPIRED";
        }
        if (normalized.contains("TOKEN_INVALID_SIGNATURE")) {
            return "TOKEN_SIGNATURE_INVALID";
        }
        if (normalized.contains("TOKEN_INVALID_AUTHORIZED_PARTIES")) {
            return "TOKEN_SOURCE_INVALID";
        }
        return "TOKEN_INVALID";
    }
    
    /**
     * 判断是否是 verla SSE 订阅请求（GET /v1/verla/conversations/{cid}/events）。
     * 仅在该路径上允许通过 ?access_token= 兜底鉴权，其它路径仍强制 Authorization header。
     * <p>
     * cid 段兼容两种格式：迁移期纯数字 path，以及 V2 带类型前缀的 public id（如 {@code vc_AbS25}）。
     * 不能再限定为 {@code \\d+}，否则 public id SSE 订阅会因取不到 access_token 而误判为 MISSING_TOKEN。
     */
    private boolean isVerlaSseRequest(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        // /v1/verla/conversations/{cid}/events，cid 可为纯数字或 public id（vc_xxx）
        return uri.matches(".*/v1/verla/conversations/[^/]+/events");
    }

    private boolean isInternalRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/v1/internal/");
    }

    /**
     * 发送 JSON 格式的错误响应
     */
    private void sendErrorResponse(HttpServletResponse response, int status, String errorCode, String message) {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        
        Map<String, Object> errorBody = new HashMap<>();
        errorBody.put("meta", Map.of(
            "status_code", status,
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
