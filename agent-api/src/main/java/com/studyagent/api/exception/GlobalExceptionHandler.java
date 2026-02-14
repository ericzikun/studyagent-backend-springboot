package com.studyagent.api.exception;

import com.studyagent.api.common.Meta;
import com.studyagent.api.common.Result;
import com.studyagent.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    /**
     * 参数验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        log.warn("参数验证失败: {}", errors);
        return Result.error(1001, "参数验证失败: " + errors);
    }
    
    /**
     * 业务异常（带错误码）
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBusinessException(BusinessException ex) {
        log.warn("业务异常: code={}, message={}", ex.getCode(), ex.getMessage());
        return Result.error(ex.getCode(), ex.getMessage());
    }

    /**
     * 业务异常（参数错误等）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("业务异常: {}", ex.getMessage());
        return Result.error(1001, ex.getMessage());
    }
    
    /**
     * 状态异常
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalStateException(IllegalStateException ex) {
        log.warn("状态异常: {}", ex.getMessage());
        return Result.error(1002, ex.getMessage());
    }
    
    /**
     * 文件上传异常
     * 
     * 处理文件上传过程中的异常，常见原因：
     * 1. 网络中断导致上传流中断
     * 2. 用户取消上传
     * 3. 文件过大超时
     * 4. 客户端连接异常关闭
     * 
     * 这类异常通常不是服务端问题，使用 WARN 级别记录，避免大量 ERROR 日志
     */
    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMultipartException(MultipartException ex) {
        // 判断是否为流中断异常
        String errorMsg = ex.getMessage();
        if (errorMsg != null && errorMsg.contains("Stream ended unexpectedly")) {
            log.warn("文件上传流中断（可能是网络问题或用户取消上传）: {}", ex.getMessage());
            return Result.error(4001, "文件上传失败：上传过程中断，请检查网络连接后重试");
        } else if (errorMsg != null && errorMsg.contains("maximum upload size")) {
            log.warn("文件上传失败：文件过大 - {}", ex.getMessage());
            return Result.error(4002, "文件上传失败：文件大小超过限制（最大 100MB）");
        } else {
            log.warn("文件上传失败: {}", ex.getMessage());
            return Result.error(4000, "文件上传失败：" + ex.getMessage());
        }
    }
    
    /**
     * 客户端连接中断异常
     * 
     * 处理客户端主动断开连接的场景，常见原因：
     * 1. 用户刷新页面或关闭浏览器标签
     * 2. 前端请求超时主动取消
     * 3. 网络不稳定导致连接中断
     * 4. 负载均衡器/代理服务器超时
     * 5. 响应数据量过大，客户端等待超时
     * 
     * 这类异常通常不是服务端问题，使用 WARN 级别记录，避免污染日志和误报告警
     * 
     * 注意：此异常不返回响应体，因为连接已断开，客户端无法接收响应
     */
    @ExceptionHandler({ClientAbortException.class, IOException.class})
    public void handleClientAbortException(Exception ex) {
        String errorMsg = ex.getMessage();
        
        // 判断是否为客户端断开相关的异常
        if (errorMsg != null && 
            (errorMsg.contains("Broken pipe") ||
             errorMsg.contains("Connection reset by peer") ||
             errorMsg.contains("ClientAbortException") ||
             ex instanceof ClientAbortException)) {
            
            // 使用 WARN 级别记录，避免污染 ERROR 日志
            log.warn("客户端断开连接 (可能是用户刷新页面、网络中断或响应超时): {} - {}", 
                    ex.getClass().getSimpleName(), 
                    errorMsg);
            
            // 不抛出异常，不返回响应(连接已断开，无法返回)
            return;
        }
        
        // 其他 IOException 按正常流程处理
        log.error("IO 异常", ex);
        throw new RuntimeException("IO 异常: " + ex.getMessage(), ex);
    }
    
    /**
     * 运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleRuntimeException(RuntimeException ex) {
        log.error("运行时异常", ex);
        return Result.error(9999, ex.getMessage());
    }
    
    /**
     * 通用异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception ex) {
        log.error("系统异常", ex);
        return Result.error(9999, "系统异常: " + ex.getMessage());
    }
}

