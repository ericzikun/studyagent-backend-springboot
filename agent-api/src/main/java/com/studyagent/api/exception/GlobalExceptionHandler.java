package com.studyagent.api.exception;

import com.studyagent.api.common.Meta;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.response.InsufficientQuotaResponse;
import com.studyagent.api.dto.response.SubmitQuotaExceededResponse;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.exception.InsufficientQuotaException;
import com.studyagent.common.exception.QuotaExceededException;
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
        return Result.error(ApiCode.PARAM_VALIDATION_FAILED, errors);
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
     * 任务提交额度超限异常
     * 返回带额度信息的响应体，供前端展示「今日已用 X/Y 次，将于 Z 时重置」
     */
    /**
     * AI 额度不足异常
     */
    @ExceptionHandler(InsufficientQuotaException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<InsufficientQuotaResponse> handleInsufficientQuotaException(InsufficientQuotaException ex) {
        log.warn("AI 额度不足: {}", ex.getMessage());
        var data = ex.getData();
        InsufficientQuotaResponse response = data != null ? InsufficientQuotaResponse.builder()
                .featureCode(data.getFeatureCode())
                .featureName(data.getFeatureName())
                .quotaUnit(data.getQuotaUnit())
                .freeBalance(data.getFreeBalance())
                .freePeriodTotal(data.getFreePeriodTotal())
                .paidBalance(data.getPaidBalance())
                .totalAvailable(data.getTotalAvailable())
                .build() : null;
        Result<InsufficientQuotaResponse> result = new Result<>();
        result.setMeta(Meta.error(InsufficientQuotaException.CODE, ex.getMessage()));
        result.setData(response);
        return result;
    }

    /**
     * 任务提交额度超限异常（每日次数模式）
     */
    @ExceptionHandler(QuotaExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<SubmitQuotaExceededResponse> handleQuotaExceededException(QuotaExceededException ex) {
        log.warn("任务提交额度超限: code={}, message={}", ex.getCode(), ex.getMessage());
        var data = ex.getQuotaData();
        SubmitQuotaExceededResponse response = SubmitQuotaExceededResponse.builder()
                .dailyLimit(data != null ? data.getDailyLimit() : null)
                .usedToday(data != null ? data.getUsedToday() : null)
                .remainingQuota(data != null ? data.getRemainingQuota() : 0)
                .quotaResetAt(data != null ? data.getQuotaResetAt() : null)
                .quotaResetAtUtc(data != null ? data.getQuotaResetAtUtc() : null)
                .build();
        Result<SubmitQuotaExceededResponse> result = new Result<>();
        result.setMeta(Meta.error(ex.getCode(), ex.getMessage()));
        result.setData(response);
        return result;
    }

    /**
     * 业务异常（参数错误等）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("业务异常: {}", ex.getMessage());
        return Result.error(ApiCode.PARAM_ERROR.getCode(), ex.getMessage());
    }
    
    /**
     * 状态异常
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalStateException(IllegalStateException ex) {
        log.warn("状态异常: {}", ex.getMessage());
        return Result.error(ApiCode.ILLEGAL_STATE);
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
            return Result.error(ApiCode.FILE_UPLOAD_STREAM_INTERRUPTED);
        } else if (errorMsg != null && errorMsg.contains("maximum upload size")) {
            log.warn("文件上传失败：文件过大 - {}", ex.getMessage());
            return Result.error(ApiCode.FILE_UPLOAD_SIZE_EXCEEDED);
        } else {
            log.warn("文件上传失败: {}", ex.getMessage());
            return Result.error(ApiCode.FILE_UPLOAD_FAILED, ex.getMessage());
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
        return Result.error(ApiCode.UNKNOWN_ERROR_WITH_MSG, ex.getMessage());
    }
    
    /**
     * 通用异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception ex) {
        log.error("系统异常", ex);
        return Result.error(ApiCode.UNKNOWN_ERROR_WITH_MSG, "System error: " + ex.getMessage());
    }
}

