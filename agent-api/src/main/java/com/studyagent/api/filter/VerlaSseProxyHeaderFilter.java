package com.studyagent.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 告知 Nginx 勿缓冲 SSE 响应，避免 HTTPS 反代下 0 bytes 挂起。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class VerlaSseProxyHeaderFilter extends OncePerRequestFilter {

    private static final Pattern SSE_PATH =
            Pattern.compile("^/v1/verla/conversations/[^/]+/events/?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SSE_PATH.matcher(request.getRequestURI()).matches()) {
            response.setHeader("X-Accel-Buffering", "no");
            response.setHeader("Cache-Control", "no-cache, no-transform");
        }
        filterChain.doFilter(request, response);
    }
}
