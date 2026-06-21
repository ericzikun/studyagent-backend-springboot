package com.studyagent.common.exception;

import com.studyagent.common.api.ApiCode;
import lombok.Getter;

/**
 * 业务异常，携带错误码
 * 由 GlobalExceptionHandler 捕获并转换为 Result.error(code, message)
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;
    private final String message;
    private final Object data;

    public BusinessException(int code, String message) {
        this(code, message, null);
    }

    public BusinessException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public BusinessException(ApiCode apiCode) {
        this(apiCode.getCode(), apiCode.getMessage());
    }

    public BusinessException(ApiCode apiCode, Object... formatArgs) {
        this(apiCode.getCode(), apiCode.formatEn(formatArgs));
    }

    public BusinessException(String message) {
        this(9999, message);
    }

    public static BusinessException withData(ApiCode apiCode, Object data) {
        return new BusinessException(apiCode.getCode(), apiCode.getMessage(), data);
    }
}
