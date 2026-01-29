package com.studyagent.common.log.aspect;

import com.studyagent.common.log.annotation.ApiLog;
import com.studyagent.common.log.util.LogUtil;
import com.studyagent.common.log.util.TraceIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API 日志切面
 * 自动记录 Controller 层的请求入参、响应出参和耗时
 */
@Slf4j
@Aspect
@Component
@Order(1)
public class ApiLogAspect {
    
    /**
     * 切入点：所有使用 @ApiLog 注解的方法
     */
    @Pointcut("@annotation(com.studyagent.common.log.annotation.ApiLog)")
    public void apiLogPointcut() {
    }
    
    /**
     * 切入点：所有 Controller 层的方法（作为兜底）
     */
    @Pointcut("execution(* com.studyagent.api.controller..*.*(..))")
    public void controllerPointcut() {
    }
    
    /**
     * 环绕通知
     */
    @Around("apiLogPointcut() || controllerPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        
        // 获取方法签名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String methodName = signature.getDeclaringTypeName() + "." + method.getName();
        
        // 获取注解配置
        ApiLog apiLog = method.getAnnotation(ApiLog.class);
        String description = "";
        boolean logRequest = true;
        boolean logResponse = true;
        boolean logHeaders = false;
        String[] sensitiveFields = {"password", "token", "secret", "authorization", "secretKey"};
        int maxLength = 4096;
        long slowThreshold = 3000;
        
        if (apiLog != null) {
            description = apiLog.description();
            logRequest = apiLog.logRequest();
            logResponse = apiLog.logResponse();
            logHeaders = apiLog.logHeaders();
            sensitiveFields = apiLog.sensitiveFields();
            maxLength = apiLog.maxLength();
            slowThreshold = apiLog.slowThreshold();
        }
        
        // 获取请求信息
        HttpServletRequest request = getHttpServletRequest();
        String httpMethod = request != null ? request.getMethod() : "UNKNOWN";
        String uri = request != null ? request.getRequestURI() : "UNKNOWN";
        String clientIp = getClientIp(request);
        
        // 确保有 TraceId
        String traceId = TraceIdUtil.getTraceId();
        
        // 构建日志前缀
        String logPrefix = String.format("[%s] [%s %s]", traceId, httpMethod, uri);
        if (!description.isEmpty()) {
            logPrefix = logPrefix + " [" + description + "]";
        }
        
        // 记录请求日志
        if (logRequest) {
            String requestLog = buildRequestLog(joinPoint, request, logHeaders, sensitiveFields, maxLength);
            log.info("{} >>> REQUEST | ip={} | {}", logPrefix, clientIp, requestLog);
        } else {
            log.info("{} >>> REQUEST | ip={}", logPrefix, clientIp);
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
                log.error("{} <<< RESPONSE | cost={} | ERROR: {}", 
                        logPrefix, costTimeStr, exception.getMessage());
            } else {
                // 正常响应
                String responseLog = "";
                if (logResponse && result != null) {
                    responseLog = " | response=" + LogUtil.toJson(result, sensitiveFields, maxLength);
                }
                
                // 根据耗时决定日志级别
                if (costTime > slowThreshold) {
                    log.warn("{} <<< RESPONSE | cost={} [SLOW]{}", logPrefix, costTimeStr, responseLog);
                } else {
                    log.info("{} <<< RESPONSE | cost={}{}", logPrefix, costTimeStr, responseLog);
                }
            }
        }
    }
    
    /**
     * 构建请求参数日志
     */
    private String buildRequestLog(ProceedingJoinPoint joinPoint, HttpServletRequest request,
                                   boolean logHeaders, String[] sensitiveFields, int maxLength) {
        StringBuilder sb = new StringBuilder();
        
        // 请求头
        if (logHeaders && request != null) {
            Map<String, String> headers = new HashMap<>();
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                headers.put(headerName, request.getHeader(headerName));
            }
            sb.append("headers=").append(LogUtil.toJson(headers, sensitiveFields, maxLength));
            sb.append(" | ");
        }
        
        // 请求参数
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        
        if (parameterNames != null && parameterNames.length > 0) {
            Map<String, Object> params = new HashMap<>();
            for (int i = 0; i < parameterNames.length; i++) {
                Object arg = args[i];
                // 过滤特殊类型
                if (arg instanceof HttpServletRequest 
                        || arg instanceof HttpServletResponse
                        || arg instanceof MultipartFile) {
                    if (arg instanceof MultipartFile) {
                        MultipartFile file = (MultipartFile) arg;
                        params.put(parameterNames[i], String.format("MultipartFile[name=%s, size=%s]", 
                                file.getOriginalFilename(), LogUtil.formatSize(file.getSize())));
                    }
                    continue;
                }
                if (arg instanceof MultipartFile[]) {
                    MultipartFile[] files = (MultipartFile[]) arg;
                    List<String> fileInfos = new ArrayList<>();
                    for (MultipartFile file : files) {
                        fileInfos.add(String.format("MultipartFile[name=%s, size=%s]", 
                                file.getOriginalFilename(), LogUtil.formatSize(file.getSize())));
                    }
                    params.put(parameterNames[i], fileInfos);
                    continue;
                }
                params.put(parameterNames[i], arg);
            }
            sb.append("params=").append(LogUtil.toJson(params, sensitiveFields, maxLength));
        }
        
        return sb.toString();
    }
    
    /**
     * 获取 HttpServletRequest
     */
    private HttpServletRequest getHttpServletRequest() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                return attributes.getRequest();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
    
    /**
     * 获取客户端 IP
     */
    private String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "UNKNOWN";
        }
        
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        
        // 多个代理时取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        
        return ip;
    }
}

