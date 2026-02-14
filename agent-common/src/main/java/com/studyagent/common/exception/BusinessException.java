package com.studyagent.common.exception;

import lombok.Getter;

/**
 * 业务异常，携带错误码
 * 由 GlobalExceptionHandler 捕获并转换为 Result.error(code, message)
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;
    private final String message;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public BusinessException(String message) {
        this(9999, message);
    }
}
