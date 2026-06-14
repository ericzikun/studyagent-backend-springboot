package com.studyagent.service.domain.billing;

import lombok.Getter;

@Getter
public class BillingDomainException extends RuntimeException {
    private final String code;

    public BillingDomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BillingDomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
