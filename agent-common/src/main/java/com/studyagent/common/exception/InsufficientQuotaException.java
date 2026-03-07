package com.studyagent.common.exception;

import com.studyagent.common.api.ApiCode;

/**
 * AI 额度不足异常
 * 当用户免费+付费额度不足以消费时抛出
 */
public class InsufficientQuotaException extends RuntimeException {

    public static final int CODE = ApiCode.INSUFFICIENT_QUOTA.getCode();
    private final InsufficientQuotaData data;

    public InsufficientQuotaException(String message, InsufficientQuotaData data) {
        super(message);
        this.data = data;
    }

    public InsufficientQuotaData getData() {
        return data;
    }
}
