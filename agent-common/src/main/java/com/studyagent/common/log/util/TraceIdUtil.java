package com.studyagent.common.log.util;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * TraceId 工具类
 * 用于请求链路追踪
 */
public class TraceIdUtil {
    
    /**
     * MDC 中的 TraceId key
     */
    public static final String TRACE_ID_KEY = "traceId";
    
    /**
     * MDC 中的 SpanId key（用于标识子请求）
     */
    public static final String SPAN_ID_KEY = "spanId";
    
    /**
     * MDC 中的用户ID key
     */
    public static final String USER_ID_KEY = "userId";
    
    /**
     * 生成 TraceId（16位十六进制字符串）
     */
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    
    /**
     * 生成 SpanId（8位十六进制字符串）
     */
    public static String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
    
    /**
     * 设置 TraceId 到 MDC
     */
    public static String setTraceId() {
        String traceId = generateTraceId();
        MDC.put(TRACE_ID_KEY, traceId);
        return traceId;
    }
    
    /**
     * 设置指定的 TraceId 到 MDC
     */
    public static void setTraceId(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
    }
    
    /**
     * 获取当前 TraceId
     */
    public static String getTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId == null) {
            traceId = setTraceId();
        }
        return traceId;
    }
    
    /**
     * 设置 SpanId 到 MDC
     */
    public static String setSpanId() {
        String spanId = generateSpanId();
        MDC.put(SPAN_ID_KEY, spanId);
        return spanId;
    }
    
    /**
     * 获取当前 SpanId
     */
    public static String getSpanId() {
        return MDC.get(SPAN_ID_KEY);
    }
    
    /**
     * 设置用户ID到 MDC
     */
    public static void setUserId(String userId) {
        if (userId != null) {
            MDC.put(USER_ID_KEY, userId);
        }
    }
    
    /**
     * 获取当前用户ID
     */
    public static String getUserId() {
        return MDC.get(USER_ID_KEY);
    }
    
    /**
     * 清除 MDC 中的所有信息
     */
    public static void clear() {
        MDC.clear();
    }
    
    /**
     * 清除 TraceId
     */
    public static void clearTraceId() {
        MDC.remove(TRACE_ID_KEY);
    }
    
    /**
     * 清除 SpanId
     */
    public static void clearSpanId() {
        MDC.remove(SPAN_ID_KEY);
    }
    
    /**
     * 清除用户ID
     */
    public static void clearUserId() {
        MDC.remove(USER_ID_KEY);
    }
}

