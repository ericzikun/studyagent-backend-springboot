package com.studyagent.common.log.annotation;

import java.lang.annotation.*;

/**
 * 外部调用日志注解
 * 用于调用外部服务（如 Python 后端、Clerk API、第三方 API）的方法
 * 自动记录请求入参、响应出参、耗时等信息
 * 
 * 使用示例：
 * <pre>
 * @ExternalLog(service = "Python后端", api = "执行任务")
 * public void executeTask(TaskId taskId) {
 *     // ...
 * }
 * </pre>
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExternalLog {
    
    /**
     * 外部服务名称（如：Python后端、Clerk、Stripe）
     */
    String service();
    
    /**
     * API 名称/描述
     */
    String api() default "";
    
    /**
     * 是否打印请求入参
     */
    boolean logRequest() default true;
    
    /**
     * 是否打印响应出参
     */
    boolean logResponse() default true;
    
    /**
     * 需要脱敏的字段名列表
     */
    String[] sensitiveFields() default {"password", "token", "secret", "authorization", "secretKey", "apiKey"};
    
    /**
     * 最大日志长度（超过则截断），-1 表示不限制
     */
    int maxLength() default 4096;
    
    /**
     * 慢请求阈值（毫秒），超过此时间会打印 WARN 日志
     */
    long slowThreshold() default 5000;
    
    /**
     * 是否忽略异常日志（有些场景异常是预期的）
     */
    boolean ignoreException() default false;
}

