package com.studyagent.service.domain.payment;

/**
 * 支付领域异常
 */
public class PaymentDomainException extends RuntimeException {

    private final String code;
    private final Object[] formatArgs;

    public PaymentDomainException(String code, String message) {
        super(message);
        this.code = code;
        this.formatArgs = null;
    }

    public PaymentDomainException(String code, String message, Object... formatArgs) {
        super(message);
        this.code = code;
        this.formatArgs = formatArgs;
    }

    public PaymentDomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.formatArgs = null;
    }

    public String getCode() {
        return code;
    }

    /**
     * 用于 ApiCode 格式化的参数，如 INVALID_PACKAGE_TYPE 需要 packageType
     */
    public Object[] getFormatArgs() {
        return formatArgs;
    }
}
