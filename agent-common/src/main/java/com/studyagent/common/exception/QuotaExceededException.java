package com.studyagent.common.exception;

import com.studyagent.common.api.ApiCode;
import lombok.Getter;

/**
 * 任务提交额度超限异常
 * <p>
 * 携带额度信息，供 GlobalExceptionHandler 转换为带 data 的响应体
 */
@Getter
public class QuotaExceededException extends RuntimeException {

    /** 额度超限错误码，前端可通过 meta.statusCode === 1010 判断 */
    public static final int CODE_QUOTA_EXCEEDED = ApiCode.QUOTA_EXCEEDED.getCode();

    private final int code;
    private final String message;
    private final QuotaExceededData quotaData;

    public QuotaExceededException(String message, QuotaExceededData quotaData) {
        super(message);
        this.code = CODE_QUOTA_EXCEEDED;
        this.message = message;
        this.quotaData = quotaData;
    }
}
