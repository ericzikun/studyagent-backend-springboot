package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import com.studyagent.service.domain.billing.BillingCatalogResult;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.BillingHostedInvoiceResult;
import com.studyagent.service.domain.billing.BillingPortalSessionResult;
import com.studyagent.service.domain.billing.BillingRecordResult;
import com.studyagent.service.domain.billing.SubscriptionResult;
import com.studyagent.service.domain.payment.PaymentConfigResult;
import com.studyagent.service.domain.payment.PaymentDomainService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
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

    @GetMapping("/records")
    public Result<List<BillingRecordResult>> records(
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        return Result.success(billingDomainService.getBillingRecords(clerkUserId));
    }

    @PostMapping("/portal-session")
    public Result<Map<String, Object>> portalSession(
            @RequestBody(required = false) BillingPortalSessionRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        try {
            BillingPortalSessionResult portalSession = billingDomainService.createBillingPortalSession(
                    clerkUserId,
                    request == null ? null : request.getReturnUrl());
            Map<String, Object> data = new HashMap<>();
            data.put("url", portalSession.getUrl());
            return Result.success(data);
        } catch (BillingDomainException e) {
            return mapBillingException(e);
        }
    }

    @PostMapping("/records/{recordId}/hosted-invoice")
    public Result<Map<String, Object>> hostedInvoice(
            @PathVariable String recordId,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        try {
            BillingHostedInvoiceResult hostedInvoice = billingDomainService.createBillingHostedInvoice(
                    clerkUserId,
                    recordId);
            Map<String, Object> data = new HashMap<>();
            data.put("url", hostedInvoice.getUrl());
            return Result.success(data);
        } catch (BillingDomainException e) {
            return mapBillingException(e);
        }
    }

    private Result<Map<String, Object>> mapBillingException(BillingDomainException e) {
        return switch (e.getCode()) {
            case "STRIPE_NOT_CONFIGURED" -> Result.error(ApiCode.STRIPE_NOT_CONFIGURED);
            case "STRIPE_CUSTOMER_NOT_FOUND" -> Result.error(ApiCode.BILLING_CUSTOMER_NOT_FOUND);
            case "BILLING_RECORD_NOT_FOUND" -> Result.error(ApiCode.BILLING_RECORD_NOT_FOUND);
            case "BILLING_INVOICE_NOT_AVAILABLE" -> Result.error(ApiCode.BILLING_INVOICE_NOT_AVAILABLE);
            case "INVALID_RETURN_URL" -> Result.error(ApiCode.INVALID_CHECKOUT_RETURN_URL, e.getMessage());
            case "STRIPE_ERROR" -> Result.error(ApiCode.STRIPE_API_ERROR, e.getMessage());
            default -> Result.error(ApiCode.INTERNAL_ERROR, e.getMessage());
        };
    }

    @Data
    static class BillingPortalSessionRequest {
        private String returnUrl;
    }
}
