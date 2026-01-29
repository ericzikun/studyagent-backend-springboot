package com.studyagent.common.log.aspect;

import com.studyagent.common.log.annotation.ExternalLog;
import com.studyagent.common.log.util.LogUtil;
import com.studyagent.common.log.util.TraceIdUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 外部调用日志切面
 * 自动记录调用外部服务（Python 后端、Clerk、Stripe 等）的请求入参、响应出参和耗时
 */
@Slf4j
@Aspect
@Component
@Order(2)
public class ExternalLogAspect {
    
    /**
     * 环绕通知：拦截所有使用 @ExternalLog 注解的方法
     */
    @Around("@annotation(externalLog)")
    public Object around(ProceedingJoinPoint joinPoint, ExternalLog externalLog) throws Throwable {
        long startTime = System.currentTimeMillis();
        
        // 获取方法签名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String methodName = method.getName();
        
        // 获取注解配置
        String service = externalLog.service();
        String api = externalLog.api();
        if (api.isEmpty()) {
            api = methodName;
        }
        
        boolean logRequest = externalLog.logRequest();
        boolean logResponse = externalLog.logResponse();
        String[] sensitiveFields = externalLog.sensitiveFields();
        int maxLength = externalLog.maxLength();
        long slowThreshold = externalLog.slowThreshold();
        boolean ignoreException = externalLog.ignoreException();
        
        // 获取 TraceId
        String traceId = TraceIdUtil.getTraceId();
        
        // 设置新的 SpanId（标识这次外部调用）
        String spanId = TraceIdUtil.setSpanId();
        
        // 构建日志前缀
        String logPrefix = String.format("[%s:%s] [EXTERNAL] [%s] [%s]", traceId, spanId, service, api);
        
        // 记录请求日志
        if (logRequest) {
            String requestLog = buildRequestLog(joinPoint, sensitiveFields, maxLength);
            log.info("{} >>> CALL | {}", logPrefix, requestLog);
        } else {
            log.info("{} >>> CALL", logPrefix);
        }
        
        Object result = null;
        Throwable exception = null;
        
        try {
            // 执行目标方法
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            exception = e;
            throw e;
        } finally {
            long costTime = System.currentTimeMillis() - startTime;
            String costTimeStr = LogUtil.formatDuration(costTime);
            
            // 记录响应日志
            if (exception != null) {
                // 异常响应
                if (ignoreException) {
                    log.warn("{} <<< RESULT | cost={} | ERROR (ignored): {}", 
                            logPrefix, costTimeStr, exception.getMessage());
                } else {
                    log.error("{} <<< RESULT | cost={} | ERROR: {}", 
                            logPrefix, costTimeStr, exception.getMessage());
                }
            } else {
                // 正常响应
                String responseLog = "";
                if (logResponse && result != null) {
                    responseLog = " | response=" + LogUtil.toJson(result, sensitiveFields, maxLength);
                }
                
                // 根据耗时决定日志级别
                if (costTime > slowThreshold) {
                    log.warn("{} <<< RESULT | cost={} [SLOW]{}", logPrefix, costTimeStr, responseLog);
                } else {
                    log.info("{} <<< RESULT | cost={}{}", logPrefix, costTimeStr, responseLog);
                }
            }
            
            // 清除 SpanId
            TraceIdUtil.clearSpanId();
        }
    }
    
    /**
     * 构建请求参数日志
     */
    private String buildRequestLog(ProceedingJoinPoint joinPoint, String[] sensitiveFields, int maxLength) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        
        if (parameterNames == null || parameterNames.length == 0) {
            return "params={}";
        }
        
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < parameterNames.length; i++) {
            params.put(parameterNames[i], args[i]);
        }
        
        return "params=" + LogUtil.toJson(params, sensitiveFields, maxLength);
    }
}

