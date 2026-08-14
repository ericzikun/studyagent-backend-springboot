package com.studyagent.infra.client.clerk;

import com.clerk.backend_api.helpers.security.VerifyToken;
import com.clerk.backend_api.helpers.security.models.TokenVerificationErrorReason;
import com.clerk.backend_api.helpers.security.models.TokenVerificationException;
import com.clerk.backend_api.helpers.security.models.TokenVerificationResponse;
import com.clerk.backend_api.helpers.security.models.VerifyTokenOptions;
import com.studyagent.common.log.annotation.ExternalLog;
import com.studyagent.infra.metrics.ExternalDependencyMetrics;
import com.studyagent.service.domain.user.ClerkClient;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Clerk 客户端实现。
 *
 * <p>认证边界只信任 Clerk 官方 SDK 已完成签名和时效校验的 claims；本类不提供
 * “只解码 JWT payload”的降级路径。用户资料查询仍通过 Clerk Backend API 完成，
 * 资料查询失败不会改变 Token 的认证结果。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClerkClientImpl implements ClerkClient {

    private final WebClient webClient;
    private final UserRepository userRepository;
    private final ExternalDependencyMetrics externalDependencyMetrics;

    @Value("${clerk.secret-key:}")
    private String clerkSecretKey;

    /** Clerk Dashboard 提供的 PEM JWT 公钥；配置后可无网络完成验签。 */
    @Value("${clerk.jwt-key:}")
    private String clerkJwtKey;

    /** 允许签发 session token 的前端来源，多个来源使用逗号分隔。 */
    @Value("${clerk.authorized-parties:}")
    private String clerkAuthorizedParties;

    @Value("${clerk.api-url:https://api.clerk.dev/v1}")
    private String clerkApiUrl;

    /**
     * 验证 Clerk session token，并从已验证 claims 构造调用方身份。
     *
     * <p>优先使用本地 JWT 公钥；未配置公钥时使用 Secret Key 让 Clerk SDK 获取并缓存
     * JWKS。配置错误或 JWKS 不可用时失败关闭，绝不回退到未验签解析。</p>
     *
     * @param token Clerk session token；为兼容旧调用方可带 Bearer 前缀
     * @return 仅由已验证 claims 构造的用户信息
     * @throws IllegalArgumentException Token 缺失、过期、被篡改或来源不受信任
     * @throws IllegalStateException 验签配置缺失或验签基础设施不可用
     */
    @Override
    @ExternalLog(service = "Clerk", api = "验证Token", logRequest = false, logResponse = false)
    public UserInfo verifyToken(String token) {
        String normalizedToken = normalizeBearerToken(token);
        if (!hasText(normalizedToken)) {
            throw new IllegalArgumentException("Invalid Clerk token: TOKEN_MISSING");
        }

        VerifyTokenOptions.Builder optionsBuilder = buildVerificationOptions();
        List<String> authorizedParties = parseAuthorizedParties();
        if (!authorizedParties.isEmpty()) {
            optionsBuilder.authorizedParties(authorizedParties);
        }

        try {
            TokenVerificationResponse<?> verification = VerifyToken.verifyToken(normalizedToken, optionsBuilder.build());
            if (!(verification.payload() instanceof Claims claims)) {
                throw new IllegalArgumentException("Invalid Clerk token: CLAIMS_MISSING");
            }
            String clerkUserId = claims.getSubject();
            if (clerkUserId == null || clerkUserId.isBlank()) {
                throw new IllegalArgumentException("Invalid Clerk token: SUBJECT_MISSING");
            }

            UserInfo userInfo = userInfoFromVerifiedClaims(claims);
            log.debug("Clerk token 验签成功: clerkUserId={}", userInfo.clerkUserId);
            return userInfo;
        } catch (TokenVerificationException e) {
            String reason = e.reason().name();
            if (isVerificationInfrastructureFailure(e.reason())) {
                log.error("Clerk token 验签基础设施不可用: reason={}", reason, e);
                throw new IllegalStateException("Clerk token verification unavailable: " + reason, e);
            }
            log.warn("Clerk token 验签失败: reason={}", reason);
            throw new IllegalArgumentException("Invalid Clerk token: " + reason, e);
        } catch (IOException e) {
            log.error("Clerk JWKS 请求失败", e);
            throw new IllegalStateException("Clerk token verification unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Clerk token verification interrupted", e);
        }
    }

    private VerifyTokenOptions.Builder buildVerificationOptions() {
        if (hasText(clerkJwtKey)) {
            // 部署平台常以字面量 \\n 保存多行 PEM，使用前恢复换行。
            return VerifyTokenOptions.jwtKey(clerkJwtKey.trim().replace("\\n", "\n"));
        }
        if (hasText(clerkSecretKey) && !"sk_test_xxx".equals(clerkSecretKey.trim())) {
            return VerifyTokenOptions.secretKey(clerkSecretKey.trim());
        }
        throw new IllegalStateException("Clerk token verification is not configured");
    }

    private List<String> parseAuthorizedParties() {
        if (!hasText(clerkAuthorizedParties)) {
            return List.of();
        }
        return Arrays.stream(clerkAuthorizedParties.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private UserInfo userInfoFromVerifiedClaims(Claims claims) {
        UserInfo userInfo = new UserInfo();
        userInfo.clerkUserId = claims.getSubject();
        userInfo.email = claims.get("email", String.class);
        userInfo.emailVerified = Boolean.TRUE.equals(claims.get("email_verified", Boolean.class));
        userInfo.displayName = Optional.ofNullable(claims.get("name", String.class))
                .or(() -> Optional.ofNullable(claims.get("username", String.class)))
                .or(() -> Optional.ofNullable(claims.get("first_name", String.class)))
                .orElse(null);
        userInfo.avatarUrl = Optional.ofNullable(claims.get("picture", String.class))
                .or(() -> Optional.ofNullable(claims.get("image_url", String.class)))
                .orElse(null);
        return userInfo;
    }

    private boolean isVerificationInfrastructureFailure(TokenVerificationErrorReason reason) {
        return reason == TokenVerificationErrorReason.JWK_FAILED_TO_LOAD
                || reason == TokenVerificationErrorReason.JWK_REMOTE_INVALID
                || reason == TokenVerificationErrorReason.JWK_LOCAL_INVALID
                || reason == TokenVerificationErrorReason.JWK_FAILED_TO_RESOLVE
                || reason == TokenVerificationErrorReason.SECRET_KEY_MISSING
                || reason == TokenVerificationErrorReason.FAILED_TO_PROCESS_RESPONSE;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeBearerToken(String token) {
        if (token == null) {
            return null;
        }
        String normalized = token.trim();
        if (normalized.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return normalized.substring(7).trim();
        }
        return normalized;
    }
    
    /**
     * 从 Clerk Backend API 获取用户信息
     * 与 Python 后端的 get_clerk_user 方法保持一致
     * 
     * 优化：添加超时控制和网络容错
     * - 如果 Clerk API 不可达，不应该阻塞用户请求
     * - 资料查询失败返回 null，不改变已经完成的 Token 验签结果
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

        ExternalDependencyMetrics.Observation observation = externalDependencyMetrics.start();
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
                    externalDependencyMetrics.error(observation, ExternalDependencyMetrics.Dependency.CLERK,
                            ExternalDependencyMetrics.Operation.REMOTE_API, e);
                    log.warn("Clerk API 请求超时 (5秒): {}", userUrl);
                    return reactor.core.publisher.Mono.empty();
                })
                .onErrorResume(io.netty.handler.timeout.ReadTimeoutException.class, e -> {
                    externalDependencyMetrics.error(observation, ExternalDependencyMetrics.Dependency.CLERK,
                            ExternalDependencyMetrics.Operation.REMOTE_API, e);
                    log.warn("Clerk API 读取超时: {}", userUrl);
                    return reactor.core.publisher.Mono.empty();
                })
                .onErrorResume(java.net.ConnectException.class, e -> {
                    externalDependencyMetrics.error(observation, ExternalDependencyMetrics.Dependency.CLERK,
                            ExternalDependencyMetrics.Operation.REMOTE_API, e);
                    log.warn("Clerk API 连接失败: {} - {}", userUrl, e.getMessage());
                    return reactor.core.publisher.Mono.empty();
                })
                .block();
            
            if (response != null) {
                externalDependencyMetrics.success(observation, ExternalDependencyMetrics.Dependency.CLERK,
                        ExternalDependencyMetrics.Operation.REMOTE_API);
                return response;
            } else {
                log.debug("Clerk API 返回空响应或请求被跳过");
            }
        } catch (WebClientResponseException e) {
            externalDependencyMetrics.error(observation, ExternalDependencyMetrics.Dependency.CLERK,
                    ExternalDependencyMetrics.Operation.REMOTE_API, e);
            log.warn("获取 Clerk 用户信息失败: Status={}, Response={}", 
                e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            externalDependencyMetrics.error(observation, ExternalDependencyMetrics.Dependency.CLERK,
                    ExternalDependencyMetrics.Operation.REMOTE_API, e);
            // 网络异常不应该阻塞认证流程，记录警告并返回 null
            log.warn("从 Clerk 获取用户信息异常，本次资料查询返回空: {}", e.getMessage());
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
    public String getUserEmail(String clerkUserId) {
        try {
            Map<String, Object> userData = getClerkUser(clerkUserId);
            if (userData != null) {
                UserInfo info = extractUserInfo(userData);
                if (info.email != null && !info.email.isEmpty()) {
                    log.debug("成功获取用户邮箱: clerkUserId={}", clerkUserId);
                    return info.email;
                }
            }
            log.warn("无法获取用户邮箱: clerkUserId={}", clerkUserId);
            return null;
        } catch (Exception e) {
            log.warn("获取用户邮箱异常: clerkUserId={}, error={}", clerkUserId, e.getMessage());
            return null;
        }
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
