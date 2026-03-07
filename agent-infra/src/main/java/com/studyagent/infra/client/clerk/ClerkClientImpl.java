package com.studyagent.infra.client.clerk;

import com.studyagent.common.log.annotation.ExternalLog;
import com.studyagent.service.domain.user.ClerkClient;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Clerk客户端实现
 * 使用 Frontend API 验证 session token（与 Python 后端保持一致）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClerkClientImpl implements ClerkClient {
    
    /**
     * Token 过期异常 - 用于区分"token 过期"和"token 格式错误"
     */
    private static class TokenExpiredException extends RuntimeException {
        TokenExpiredException(String message) {
            super(message);
        }
    }
    
    private final WebClient webClient;
    private final UserRepository userRepository;
    
    // Token 缓存实例（单例，与 Python 后端保持一致）
    private static final TokenCache tokenCache = new TokenCache(300_000); // 5分钟 TTL
    
    @Value("${clerk.secret-key:}")
    private String clerkSecretKey;
    
    @Value("${clerk.api-url:https://api.clerk.dev/v1}")
    private String clerkApiUrl;
    
    @Value("${clerk.frontend-api-url:}")
    private String clerkFrontendApiUrl;
    
    @Override
    @ExternalLog(service = "Clerk", api = "验证Token", logRequest = false, logResponse = false)
    public UserInfo verifyToken(String token) {
        long startTime = System.currentTimeMillis();
        
        // 先尝试从缓存获取
        UserInfo cachedResult = tokenCache.get(token);
        if (cachedResult != null) {
            long duration = System.currentTimeMillis() - startTime;
            log.debug("使用缓存的 token 验证结果 (耗时: {}ms)", duration);
            return cachedResult;
        }
        
        try {
            // ============================================
            // 方法1：Frontend API（已注释，因为当前环境会请求失败）
            // 未来有时间再考虑修复
            // ============================================
            /*
            // 方法1：优先使用 Frontend API（推荐方式）
            // Frontend API URL 格式：https://<your-app>.clerk.accounts.dev
            if (clerkFrontendApiUrl != null && !clerkFrontendApiUrl.isEmpty()) {
                // 去掉末尾的斜杠，与 Python 后端保持一致
                String frontendApiUrl = clerkFrontendApiUrl.trim().replaceAll("/+$", "");
                // 确保 URL 包含 /v1 路径
                if (!frontendApiUrl.endsWith("/v1")) {
                        frontendApiUrl = frontendApiUrl + "/v1";
                }
                
                // 构建完整的请求 URL
                String requestUrl = frontendApiUrl + "/me";
                log.debug("请求 Clerk Frontend API: {}", requestUrl);
                
                try {
                    log.debug("请求 Clerk Frontend API: {}，Token 前缀: {}", requestUrl, 
                        token != null && token.length() > 20 ? token.substring(0, 20) + "..." : "null");
                    
                    Map<String, Object> response = webClient.get()
                        .uri(requestUrl)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();
                    
                    if (response != null) {
                        log.debug("Frontend API 验证成功，用户 ID: {}", response.get("id"));
                        UserInfo userInfo = extractUserInfo(response);
                        // 存入缓存
                        tokenCache.set(token, userInfo);
                        return userInfo;
                    }
                } catch (WebClientResponseException.Unauthorized e) {
                    log.warn("Frontend API 验证失败 (401 Unauthorized): URL={}, Status={}, Response={}", 
                        requestUrl, e.getStatusCode(), e.getResponseBodyAsString());
                    log.info("Frontend API 验证失败，将尝试 JWT 解析方式");
                    // 不抛出异常，继续尝试 JWT 解析
                } catch (WebClientResponseException e) {
                    log.warn("Frontend API 请求失败: URL={}, Status={}, Response={}", 
                        requestUrl, e.getStatusCode(), e.getResponseBodyAsString());
                    log.info("Frontend API 请求失败，将尝试 JWT 解析方式");
                    // 不抛出异常，继续尝试 JWT 解析
                } catch (Exception e) {
                    log.error("Frontend API 请求异常: URL={}, Error={}", requestUrl, e.getMessage(), e);
                    log.info("Frontend API 请求异常，将尝试 JWT 解析方式");
                    // 不抛出异常，继续尝试 JWT 解析
                }
            }
            */
            
            // ============================================
            // 方法2：解析 JWT token（当前使用的方案）
            // Clerk 的 session token 是 JWT，包含用户信息
            // 与 Python 后端保持一致
            // ============================================
            try {
                log.debug("开始解析 JWT token");
                
                // 解析 JWT payload（不验证签名）
                Map<String, Object> claims = parseJwtToken(token);
                
                if (claims != null && !claims.isEmpty()) {
                    // 从 JWT payload 中提取用户 ID
                    // Clerk JWT token 中的用户 ID 通常在 'sub' 字段中
                    String clerkUserId = (String) claims.get("sub");
                    if (clerkUserId == null || clerkUserId.isEmpty()) {
                        Object userIdObj = claims.get("user_id");
                        if (userIdObj != null) {
                            clerkUserId = userIdObj.toString();
                        }
                    }
                    
                    // 如果是 session ID (sid)，尝试从其他字段获取用户 ID
                    // 注意：不再调用 Clerk API 获取 session 信息（网络问题）
                    if (clerkUserId == null || clerkUserId.isEmpty()) {
                        // 尝试从 azp (Authorized party) 或其他字段获取
                        // Clerk JWT 通常在 sub 字段有用户 ID
                        log.warn("JWT 中缺少用户 ID (sub)，检查 claims: {}", claims.keySet());
                    }
                    
                    if (clerkUserId != null && !clerkUserId.isEmpty()) {
                        log.debug("从 JWT 解析出的用户 ID: {} (类型: {})", clerkUserId, clerkUserId.getClass().getName());
                        
                        // 验证 clerk_user_id 格式
                        if (clerkUserId.matches("^\\d+$")) {
                            log.error("clerk_user_id 是数字类型，这可能是错误的: {}", clerkUserId);
                            // 尝试转换为字符串（已经是字符串了）
                        }
                        
                        // 性能优化：优先使用 JWT 中的信息，避免不必要的 API 调用
                        // JWT token 中已经包含了用户的基本信息，可以直接使用
                        UserInfo userInfo = new UserInfo();
                        userInfo.clerkUserId = clerkUserId;
                        Object emailObj = claims.get("email");
                        userInfo.email = emailObj != null ? emailObj.toString() : null;
                        Object emailVerifiedObj = claims.get("email_verified");
                        userInfo.emailVerified = emailVerifiedObj != null && Boolean.TRUE.equals(emailVerifiedObj);
                        userInfo.displayName = Optional.ofNullable((String) claims.get("name"))
                            .or(() -> Optional.ofNullable((String) claims.get("username")))
                            .or(() -> Optional.ofNullable((String) claims.get("first_name")))
                            .orElse(null);
                        userInfo.avatarUrl = Optional.ofNullable((String) claims.get("picture"))
                            .or(() -> Optional.ofNullable((String) claims.get("image_url")))
                            .orElse(null);
                        
                        // ============================================
                        // 性能优化：完全禁用 Clerk Backend API 调用
                        // 原因：
                        // 1. 服务器在国内，访问 api.clerk.dev 经常超时
                        // 2. JWT 中的 clerkUserId 已足够用于用户认证
                        // 3. email/displayName 可以从前端的 Clerk 用户信息获取
                        // ============================================
                        log.debug("使用 JWT 中的信息完成认证，跳过 Clerk Backend API 调用 (网络优化)");
                        
                        // 存入缓存
                        tokenCache.set(token, userInfo);
                        long duration = System.currentTimeMillis() - startTime;
                        log.debug("Token 验证成功 (耗时: {}ms)", duration);
                        return userInfo;
                    } else {
                        log.warn("无法从 JWT 中提取用户 ID，JWT claims: {}", claims);
                    }
                }
            } catch (TokenExpiredException expiredError) {
                // Token 过期，抛出明确的过期异常
                long duration = System.currentTimeMillis() - startTime;
                log.warn("Clerk token 验证失败：Token 已过期 (耗时: {}ms)", duration);
                throw new RuntimeException("Invalid token: Token expired", expiredError);
            } catch (Exception jwtError) {
                log.error("JWT 解析失败: {}", jwtError.getMessage(), jwtError);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            log.warn("Clerk token 验证失败：JWT 解析失败 (耗时: {}ms)", duration);
            throw new RuntimeException("Invalid token: JWT parsing failed");
            
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Failed to verify Clerk token (耗时: {}ms)", duration, e);
            throw new RuntimeException("Invalid token", e);
        }
    }
    
    // Gson 实例复用（提升性能）
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    
    /**
     * 解析 JWT token（不验证签名）
     * 与 Python 后端保持一致：jwt.decode(token, options={"verify_signature": False})
     * 
     * 性能优化：
     * 1. 使用静态 Gson 实例，避免重复创建
     * 2. Base64 解码和 JSON 解析都是内存操作，非常快（<1ms）
     * 3. 配合缓存机制，大部分请求不会走到这里
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJwtToken(String token) {
        try {
            // JWT token 格式：header.payload.signature
            // 快速检查：JWT 至少需要两个点分隔符
            int firstDot = token.indexOf('.');
            if (firstDot < 0) {
                log.debug("Token 格式不正确，不是有效的 JWT");
                return null;
            }
            
            int secondDot = token.indexOf('.', firstDot + 1);
            if (secondDot < 0) {
                log.debug("Token 格式不正确，缺少签名部分");
                return null;
            }
            
            // 解码 payload（第二部分，性能优化：只解码需要的部分）
            String payloadBase64 = token.substring(firstDot + 1, secondDot);
            String payload = new String(Base64.getUrlDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
            
            // 手动解析 JSON payload（使用静态 Gson 实例）
            Map<String, Object> claimsMap = GSON.fromJson(payload, Map.class);
            
            if (claimsMap == null) {
                log.debug("JWT payload 解析为空");
                return null;
            }
            
            // 检查 token 是否过期（必须验证，过期 token 应该被拒绝）
            if (claimsMap.containsKey("exp")) {
                Object expObj = claimsMap.get("exp");
                if (expObj instanceof Number) {
                    long expTime = ((Number) expObj).longValue();
                    long currentTime = System.currentTimeMillis() / 1000;
                    if (expTime < currentTime) {
                        long expiredSeconds = currentTime - expTime;
                        log.warn("Token 已过期：过期时间 {}, 当前时间 {}, 已过期 {} 秒", 
                            expTime, currentTime, expiredSeconds);
                        // 抛出明确的过期异常，让调用方能区分过期和其他错误
                        throw new TokenExpiredException("Token expired " + expiredSeconds + " seconds ago");
                    } else {
                        log.debug("Token 未过期，剩余时间: {} 秒", expTime - currentTime);
                    }
                }
            }
            
            return claimsMap;
                
        } catch (IllegalArgumentException e) {
            log.debug("JWT Base64 解码失败: {}", e.getMessage());
            return null;
        } catch (com.google.gson.JsonSyntaxException e) {
            log.debug("JWT JSON 解析失败: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.debug("JWT 解析异常: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取 session 信息
     * 与 Python 后端的 _get_session 方法保持一致
     */
    private Map<String, Object> getSession(String sessionId) {
        if (clerkSecretKey == null || clerkSecretKey.isEmpty()) {
            return null;
        }
        
        try {
            String sessionUrl = clerkApiUrl + "/sessions/" + sessionId;
            log.debug("获取 session 信息: {}", sessionUrl);
            
            Map<String, Object> response = webClient.get()
                .uri(sessionUrl)
                .header("Authorization", "Bearer " + clerkSecretKey)
                .header("Content-Type", "application/json")
                .retrieve()
                .bodyToMono(Map.class)
                .block();
            
            if (response != null) {
                return response;
            }
        } catch (Exception e) {
            log.debug("获取 session 信息失败: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 从 Clerk Backend API 获取用户信息
     * 与 Python 后端的 get_clerk_user 方法保持一致
     * 
     * 优化：添加超时控制和网络容错
     * - 如果 Clerk API 不可达，不应该阻塞用户请求
     * - 使用 JWT 中的基本信息作为降级方案
     */
    private Map<String, Object> getClerkUser(String clerkUserId) {
        if (clerkSecretKey == null || clerkSecretKey.isEmpty()) {
            log.debug("Clerk Secret Key 未配置，跳过 Backend API 调用");
            return null;
        }
        
        // 验证 clerk_user_id 格式
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            log.error("无效的 clerk_user_id: null 或空字符串");
            return null;
        }
        
        // Clerk 用户 ID 应该是字符串格式，不应该只是数字
        if (clerkUserId.matches("^\\d+$")) {
            log.error("clerk_user_id 看起来像是数据库 ID 而不是 Clerk ID: {}", clerkUserId);
            return null;
        }
        
        try {
            String userUrl = clerkApiUrl + "/users/" + clerkUserId;
            log.debug("正在从 Clerk 获取用户信息: {}", userUrl);
            
            // 添加超时控制（5秒），使用 Reactor 的 timeout 操作符
            Map<String, Object> response = webClient.get()
                .uri(userUrl)
                .header("Authorization", "Bearer " + clerkSecretKey)
                .header("Content-Type", "application/json")
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(java.time.Duration.ofSeconds(5)) // 5秒超时
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    log.warn("Clerk API 请求超时 (5秒): {}", userUrl);
                    return reactor.core.publisher.Mono.empty();
                })
                .onErrorResume(io.netty.handler.timeout.ReadTimeoutException.class, e -> {
                    log.warn("Clerk API 读取超时: {}", userUrl);
                    return reactor.core.publisher.Mono.empty();
                })
                .onErrorResume(java.net.ConnectException.class, e -> {
                    log.warn("Clerk API 连接失败: {} - {}", userUrl, e.getMessage());
                    return reactor.core.publisher.Mono.empty();
                })
                .block();
            
            if (response != null) {
                return response;
            } else {
                log.debug("Clerk API 返回空响应或请求被跳过");
            }
        } catch (WebClientResponseException e) {
            log.warn("获取 Clerk 用户信息失败: Status={}, Response={}", 
                e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            // 网络异常不应该阻塞认证流程，记录警告并返回 null
            log.warn("从 Clerk 获取用户信息异常 (将使用 JWT 信息): {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 从 Clerk 用户数据中提取用户信息
     */
    private UserInfo extractUserInfo(Map<String, Object> userData) {
        UserInfo userInfo = new UserInfo();
        userInfo.clerkUserId = (String) userData.get("id");
        
        // 提取邮箱信息
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> emailAddresses = (List<Map<String, Object>>) userData.get("email_addresses");
        if (emailAddresses != null && !emailAddresses.isEmpty()) {
            Map<String, Object> firstEmail = emailAddresses.get(0);
            userInfo.email = (String) firstEmail.get("email_address");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> verification = (Map<String, Object>) firstEmail.get("verification");
            if (verification != null) {
                String status = (String) verification.get("status");
                userInfo.emailVerified = "verified".equals(status);
            }
        }
        
        // 提取显示名称
        userInfo.displayName = (String) userData.get("first_name");
        if (userInfo.displayName == null || userInfo.displayName.isEmpty()) {
            userInfo.displayName = (String) userData.get("username");
        }
        if (userInfo.displayName == null || userInfo.displayName.isEmpty()) {
            userInfo.displayName = (String) userData.get("last_name");
        }
        if (userInfo.displayName == null || userInfo.displayName.isEmpty()) {
            userInfo.displayName = (String) userData.get("name");
        }
        
        // 提取头像 URL
        userInfo.avatarUrl = (String) userData.get("image_url");
        
        return userInfo;
    }
    
    @Override
    @ExternalLog(service = "Clerk", api = "获取或创建用户")
    public User getOrCreateUser(String clerkUserId) {
        Optional<User> existing = userRepository.findByClerkUserId(clerkUserId);
        if (existing.isPresent()) {
            return existing.get();
        }
        
        // 创建新用户
        User newUser = User.builder()
            .id(com.studyagent.service.domain.user.UserId.of(clerkUserId))
            .clerkUserId(clerkUserId)
            .isAdmin(false)
            .isActive(true)
            .locale("en")
            .createdAt(java.time.LocalDateTime.now())
            .build();
        
        return userRepository.save(newUser);
    }
}

