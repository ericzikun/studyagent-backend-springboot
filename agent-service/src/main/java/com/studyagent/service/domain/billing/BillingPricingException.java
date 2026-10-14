package com.studyagent.service.domain.billing;

import lombok.Getter;

/** 报价变化的稳定业务码及最新确认数据，不包含 Stripe 错误/密钥。 */
@Getter
public class BillingPricingException extends BillingDomainException {
    private final BillingQuote quote;
    public BillingPricingException(String code, BillingQuote quote) {
        super(code, "Refresh and confirm the current price");
        this.quote = quote;
    }
}
