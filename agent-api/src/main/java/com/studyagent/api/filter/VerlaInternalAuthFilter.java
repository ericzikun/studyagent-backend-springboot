package com.studyagent.api.filter;

import com.studyagent.common.verla.util.VerlaCidrMatcher;
import com.studyagent.common.verla.util.VerlaHmacUtil;
import jakarta.servlet.ReadListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
    /**
     * 与现网 {@code /v1/internal/reports/**} 风格保持一致，所有内部接口统一在 {@code /v1/internal/} 下。
     * 文档 §10 / §23 中写为 "/internal/" 是逻辑命名，对外实现都加上 {@code /v1} 前缀。
     */
    public static final String INTERNAL_PREFIX = "/v1/internal/verla/";

    @Value("${verla.internal.token:}")
    private String token;

    @Value("${verla.internal.hmac-secret:}")
    private String hmacSecret;

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

        byte[] bodyBytes = request.getInputStream().readAllBytes();
        String secret = StringUtils.isNotBlank(hmacSecret) ? hmacSecret : token;
        String expect = VerlaHmacUtil.sha256Hex(secret, bodyBytes);
        String sig = request.getHeader(HEADER_SIG);
        if (!VerlaHmacUtil.constantTimeEquals(expect, sig)) {
            log.warn("[verla-internal] bad signature from ip={}, uri={}", clientIp, uri);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "bad signature");
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, bodyBytes), response);
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

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body == null ? new byte[0] : body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // Synchronous request processing; no async listener is needed here.
                }

                @Override
                public int read() {
                    return in.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
