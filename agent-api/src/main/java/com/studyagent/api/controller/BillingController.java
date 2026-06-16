package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import com.studyagent.service.domain.billing.BillingCatalogResult;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.SubscriptionResult;
import com.studyagent.service.domain.payment.PaymentConfigResult;
import com.studyagent.service.domain.payment.PaymentDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/billing")
@RequiredArgsConstructor
public class BillingController {
    private final BillingDomainService billingDomainService;
    private final PaymentDomainService paymentDomainService;

    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        PaymentConfigResult paymentConfig = paymentDomainService.getPaymentConfig();
        BillingCatalogResult catalog = billingDomainService.getCatalog();
        Map<String, Object> data = new HashMap<>();
        data.put("version", "v2");
        data.put("stripePublishableKey", paymentConfig.getStripePublishableKey());
        data.put("plans", catalog.getPlans());
        data.put("addons", catalog.getAddons());
        return Result.success(data);
    }

    @GetMapping("/account")
    public Result<SubscriptionResult> account(
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        return Result.success(billingDomainService.getCurrentSubscription(clerkUserId));
    }
}
