package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.BillingPricingException;
import java.util.HashMap;
import java.util.Map;

/** 仅暴露稳定业务码与报价；不向客户端泄漏 Stripe 配置错误。 */
final class PricingErrors {
    private PricingErrors() {}
    @SuppressWarnings("unchecked")
    static <T> Result<T> result(BillingDomainException exception) {
        Result<T> result = Result.error(ApiCode.BAD_REQUEST, switch (exception.getCode()) {
            case "PRICING_CURRENCY_UNAVAILABLE" -> "This product is not available in your billing currency";
            case "PRICING_NOT_ELIGIBLE" -> "This purchase is not available for your account";
            default -> "Review and confirm the current price";
        });
        Map<String, Object> data = new HashMap<>();
        data.put("code", exception.getCode());
        if (exception instanceof BillingPricingException changed) data.put("quote", changed.getQuote());
        result.setData((T) data);
        return result;
    }
}
