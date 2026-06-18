package com.studyagent.api.interceptor;

import com.clerk.backend_api.helpers.security.AuthenticateRequest;
import com.clerk.backend_api.helpers.security.models.AuthenticateRequestOptions;
import com.clerk.backend_api.helpers.security.models.RequestState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.log.util.TraceIdUtil;
import com.studyagent.service.domain.user.ClerkClient;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.*;

/**
 * 认证拦截器
 * 
 * 增强版：
 * 1. 支持使用 Clerk 官方 SDK 验证 token（验证 JWT 签名，更安全）
 * 2. 提供详细的错误信息，帮助前端做出正确的响应
 * 3. 可配置是否使用 SDK 验证（通过 clerk.enable-sdk-verification 配置）
 * 
 * @see <a href="https://github.com/clerk/clerk-sdk-java">Clerk Java SDK</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    
    private final ClerkClient clerkClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Clerk Secret Key，用于 SDK 验证
     */
    @Value("${clerk.secret-key:}")
    private String clerkSecretKey;
    
    /**
     * 是否启用 Clerk SDK 验证
     * 启用后会调用 Clerk 官方 SDK 验证 token（验证 JWT 签名），更安全但可能有网络延迟
     * 默认关闭，使用本地 JWT 解析（已修复缓存问题）
     */
    @Value("${clerk.enable-sdk-verification:false}")
    private boolean enableSdkVerification;

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
            ClerkClient.UserInfo userInfo;
            
            // 根据配置选择验证方式
            if (enableSdkVerification && clerkSecretKey != null && !clerkSecretKey.isEmpty()
                    && !clerkSecretKey.equals("sk_test_xxx")) {
                // Verla SSE：JWT 仅能通过 ?access_token= 传递；Clerk AuthenticateRequest 常报 SESSION_TOKEN_MISSING，
                // 与 EventSource 限制不符。此处与其它降级路径一致，使用 JWT 解析（校验 exp / sub）。
                if (isVerlaSseRequest(request)) {
                    log.debug("[AuthInterceptor] Verla SSE 跳过 Clerk AuthenticateRequest，使用 JWT 解析");
                    userInfo = clerkClient.verifyToken(token);
                } else {
                    userInfo = verifyWithClerkSdk(request, token);
                }
            } else {
                userInfo = clerkClient.verifyToken(token);
            }
            
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
        } catch (RuntimeException e) {
            String errorMessage = e.getMessage();
            String errorCode = "TOKEN_INVALID";
            
            // 根据错误类型提供更精确的错误码
            if (errorMessage != null) {
                if (errorMessage.contains("expired")) {
                    errorCode = "TOKEN_EXPIRED";
                } else if (errorMessage.contains("JWT parsing failed")) {
                    errorCode = "TOKEN_MALFORMED";
                } else if (errorMessage.contains("signature")) {
                    errorCode = "TOKEN_SIGNATURE_INVALID";
                }
            }
            
            log.warn("[AuthInterceptor] Token验证失败: {} (错误码: {}), URI: {}", 
                errorMessage, errorCode, request.getRequestURI());
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, 
                errorCode, errorMessage != null ? errorMessage : "Token validation failed");
            return false;
        } catch (Exception e) {
            log.error("[AuthInterceptor] Token验证异常: {}, URI: {}", 
                e.getMessage(), request.getRequestURI(), e);
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "AUTH_ERROR", "Authentication service error");
            return false;
        }
    }
    
    /**
     * 使用 Clerk 官方 SDK 验证 token
     * 
     * 优点：
     * 1. 验证 JWT 签名，确保 token 未被篡改
     * 2. 使用官方库，安全性更高
     * 3. 自动处理 token 过期等情况
     * 
     * @param servletRequest Servlet 请求
     * @param token Bearer token
     * @return 用户信息，验证失败返回 null 或抛出异常
     */
    private ClerkClient.UserInfo verifyWithClerkSdk(HttpServletRequest servletRequest, String token) {
        try {
            // 构建 headers map（Clerk SDK 需要 Map<String, List<String>> 格式）
            Map<String, List<String>> headersMap = new HashMap<>();
            Enumeration<String> headerNames = servletRequest.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                List<String> headerValues = Collections.list(servletRequest.getHeaders(headerName));
                headersMap.put(headerName.toLowerCase(), headerValues);
            }

            // Clerk AuthenticateRequest 只认 HTTP headers；Verla SSE 的 JWT 在 query access_token，
            // preHandle 已解析为 token，必须显式注入 Authorization，否则会报 SESSION_TOKEN_MISSING。
            headersMap.put("authorization", Collections.singletonList("Bearer " + token));

            // 构建验证选项
            AuthenticateRequestOptions options = AuthenticateRequestOptions
                .secretKey(clerkSecretKey)
                .build();
            
            // 调用 Clerk SDK 验证
            RequestState requestState = AuthenticateRequest.authenticateRequest(headersMap, options);
            
            if (requestState.isSignedIn()) {
                // 验证成功，从 claims 中提取用户信息
                Optional<Claims> claimsOpt = requestState.claims();
                
                ClerkClient.UserInfo userInfo = new ClerkClient.UserInfo();
                
                if (claimsOpt.isPresent()) {
                    Claims claims = claimsOpt.get();
                    userInfo.clerkUserId = claims.getSubject(); // sub claim
                    userInfo.email = claims.get("email", String.class);
                    userInfo.emailVerified = Boolean.TRUE.equals(claims.get("email_verified", Boolean.class));
                    
                    // 尝试获取显示名称
                    userInfo.displayName = claims.get("name", String.class);
                    if (userInfo.displayName == null) {
                        userInfo.displayName = claims.get("username", String.class);
                    }
                    if (userInfo.displayName == null) {
                        userInfo.displayName = claims.get("first_name", String.class);
                    }
                    
                    // 尝试获取头像
                    userInfo.avatarUrl = claims.get("picture", String.class);
                    if (userInfo.avatarUrl == null) {
                        userInfo.avatarUrl = claims.get("image_url", String.class);
                    }
                }
                
                log.debug("[AuthInterceptor] Clerk SDK 验证成功: userId={}", userInfo.clerkUserId);
                return userInfo;
            } else {
                // 验证失败，记录原因
                String reason = requestState.reason()
                    .map(Object::toString)
                    .orElse("Unknown reason");
                log.warn("[AuthInterceptor] Clerk SDK 验证失败: {}", reason);
                
                // 根据失败原因抛出适当的异常
                if (reason.contains("expired") || reason.contains("EXPIRED")) {
                    throw new RuntimeException("Invalid token: Token expired");
                }
                throw new RuntimeException("Invalid token: " + reason);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AuthInterceptor] Clerk SDK 验证异常: {}", e.getMessage(), e);
            // SDK 验证失败时，降级使用本地 JWT 解析
            log.info("[AuthInterceptor] SDK 验证失败，降级使用本地 JWT 解析");
            return clerkClient.verifyToken(token);
        }
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
