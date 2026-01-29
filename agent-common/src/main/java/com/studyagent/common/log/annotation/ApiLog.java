package com.studyagent.common.log.annotation;

import java.lang.annotation.*;

/**
 * API 日志注解
 * 用于 Controller 层方法，自动记录请求入参、响应出参、耗时等信息
 * 
 * 使用示例：
 * <pre>
 * @ApiLog(description = "提交任务")
 * @PostMapping("/submit")
 * public Result<SubmitTaskResponse> submitTask(@RequestBody SubmitTaskRequest request) {
 *     // ...
 * }
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ApiLog {
    
    /**
     * 接口描述/操作名称
     */
    String description() default "";
    
    /**
     * 是否打印请求入参
     */
    boolean logRequest() default true;
    
    /**
     * 是否打印响应出参
     */
    boolean logResponse() default true;
    
    /**
     * 是否打印请求头
     */
    boolean logHeaders() default false;
    
    /**
     * 需要脱敏的字段名列表（支持嵌套路径，如 "user.password"）
     */
    String[] sensitiveFields() default {"password", "token", "secret", "authorization", "secretKey"};
    
    /**
     * 最大日志长度（超过则截断），-1 表示不限制
     */
    int maxLength() default 4096;
    
    /**
     * 慢请求阈值（毫秒），超过此时间会打印 WARN 日志
     */
    long slowThreshold() default 3000;
}

