package com.studyagent.common.log.filter;

import com.studyagent.common.log.util.TraceIdUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * TraceId 过滤器
 * 在请求开始时生成 TraceId，请求结束时清除
 * TraceId 会放入 MDC 中，供日志框架使用
 */
@Slf4j
@Component
@Order(1)
public class TraceIdFilter implements Filter {
    
    /**
     * 请求头中的 TraceId key（用于分布式追踪）
     */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    
    /**
     * 响应头中的 TraceId key
     */
    public static final String TRACE_ID_RESPONSE_HEADER = "X-Trace-Id";
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.info("TraceIdFilter initialized");
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        try {
            // 尝试从请求头获取 TraceId（支持分布式追踪）
            String traceId = httpRequest.getHeader(TRACE_ID_HEADER);
            if (traceId == null || traceId.isEmpty()) {
                // 如果请求头没有，则生成新的 TraceId
                traceId = TraceIdUtil.setTraceId();
            } else {
                TraceIdUtil.setTraceId(traceId);
            }
            
            // 将 TraceId 放入响应头，方便前端或调用方追踪
            httpResponse.setHeader(TRACE_ID_RESPONSE_HEADER, traceId);
            
            // 继续处理请求
            chain.doFilter(request, response);
        } finally {
            // 请求结束时清除 MDC
            TraceIdUtil.clear();
        }
    }
    
    @Override
    public void destroy() {
        log.info("TraceIdFilter destroyed");
    }
}

