package com.studyagent.api.filter;

import com.studyagent.common.verla.util.VerlaCidrMatcher;
import com.studyagent.common.verla.util.VerlaHmacUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Verla /internal/* 鉴权 Filter（仅作用于 /internal 前缀）
 * <p>
 * 对应文档 §23.1
 * <ul>
 *     <li>L1：客户端 IP 白名单（CIDR）</li>
 *     <li>L2：共享 Token + HMAC-SHA256 签名</li>
 *     <li>L3：TLS（生产环境，由网关层负责）</li>
 * </ul>
 */
@Slf4j
public class VerlaInternalAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_TOKEN = "X-Verla-Internal-Token";
    public static final String HEADER_SIG   = "X-Verla-Signature";
    public static final String INTERNAL_PREFIX = "/internal/";

    @Value("${verla.internal.token:}")
    private String token;

    @Value("${verla.internal.allow-cidrs:127.0.0.1/32}")
    private List<String> allowCidrs;

    @Value("${verla.internal.skip-hmac-on-get:true}")
    private boolean skipHmacOnGet;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        // 双保险：URL pattern 已经收敛了，这里再判一次
        if (!uri.startsWith(INTERNAL_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        if (!VerlaCidrMatcher.match(clientIp, allowCidrs)) {
            log.warn("[verla-internal] reject ip={}, uri={}", clientIp, uri);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "ip not allowed");
            return;
        }

        if (StringUtils.isBlank(token)) {
            log.error("[verla-internal] verla.internal.token NOT configured! reject all internal calls");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "internal token not configured");
            return;
        }

        String headerToken = request.getHeader(HEADER_TOKEN);
        if (!VerlaHmacUtil.constantTimeEquals(headerToken, token)) {
            log.warn("[verla-internal] bad token from ip={}, uri={}", clientIp, uri);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "bad token");
            return;
        }

        boolean isGet = "GET".equalsIgnoreCase(request.getMethod());
        if (isGet && skipHmacOnGet) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper cached = new ContentCachingRequestWrapper(request);
        // 必须先消耗 body，再校验 HMAC
        cached.getInputStream().readAllBytes();
        String body = new String(cached.getContentAsByteArray(), StandardCharsets.UTF_8);
        String expect = VerlaHmacUtil.sha256Hex(token, body);
        String sig = request.getHeader(HEADER_SIG);
        if (!VerlaHmacUtil.constantTimeEquals(expect, sig)) {
            log.warn("[verla-internal] bad signature from ip={}, uri={}", clientIp, uri);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "bad signature");
            return;
        }
        filterChain.doFilter(cached, response);
    }

    /**
     * 解析客户端 IP：优先从代理头取，最后回落到 RemoteAddr
     */
    private static String resolveClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(xff)) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (StringUtils.isNotBlank(real)) {
            return real.trim();
        }
        return req.getRemoteAddr();
    }
}
