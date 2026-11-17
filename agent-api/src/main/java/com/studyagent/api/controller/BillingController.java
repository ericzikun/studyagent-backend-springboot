package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import com.studyagent.service.domain.billing.BillingCatalogResult;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.BillingHostedInvoiceResult;
import com.studyagent.service.domain.billing.BillingPortalSessionResult;
import com.studyagent.service.domain.billing.BillingRecordPageResult;
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
import org.springframework.web.bind.annotation.RequestParam;
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
        data.put("regionalPricingEnabled", catalog.isRegionalPricingEnabled());
        data.put("stripePublishableKey", paymentConfig.getStripePublishableKey());
        data.put("plans", catalog.getPlans());
        data.put("addons", catalog.getAddons());
        // null 表示通行证未启用（Stripe Price 未就绪），前端据此不展示售卖入口。
        data.put("studyPass", catalog.getStudyPass());
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

    /** Retains direct Java callers of the legacy quote signature. */
    public Result<com.studyagent.service.domain.billing.BillingQuote> quote(String kind, String skuCode, String clerkUserId) {
        return quote(kind, skuCode, null, clerkUserId);
    }

    @GetMapping("/quote")
    public Result<com.studyagent.service.domain.billing.BillingQuote> quote(
            @RequestParam String kind, @RequestParam String skuCode,
            @RequestParam(value = "recoveryId", required = false) String recoveryId,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        try {
            if ("subscription_replacement".equals(kind))
                return Result.success(billingDomainService.getRecoveryReplacementQuote(clerkUserId, recoveryId, skuCode));
            return Result.success("credit_pack".equals(kind)
                    ? paymentDomainService.getPurchaseQuote(clerkUserId, skuCode)
                    : billingDomainService.getPurchaseQuote(clerkUserId, kind, skuCode));
        } catch (BillingDomainException e) { return mapBillingException(e); }
        catch (com.studyagent.common.exception.BusinessException e) { return PricingErrors.business(e); }
    }

    @GetMapping("/records")
    public Result<BillingRecordPageResult> records(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        try {
            return Result.success(billingDomainService.getBillingRecords(clerkUserId, cursor, limit));
        } catch (BillingDomainException e) {
            return mapBillingException(e);
        }
    }

    @PostMapping("/portal-session")
    public Result<Map<String, Object>> portalSession(
            @RequestBody(required = false) BillingPortalSessionRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        try {
            BillingPortalSessionResult portalSession = request == null || request.getPurpose() == null
                    ? billingDomainService.createBillingPortalSession(clerkUserId, request == null ? null : request.getReturnUrl())
                    : billingDomainService.createRecoveryAwarePortalSession(clerkUserId, request.getReturnUrl(), request.getPurpose());
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

    @PostMapping("/recoveries/{id}/hosted-invoice")
    public Result<Map<String, Object>> recoveryHostedInvoice(
            @PathVariable String id,
            @RequestAttribute(value = "clerkUserId", required = false) String user) {
        if (user == null || user.isBlank()) return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        try { return Result.success(Map.of("url", billingDomainService.createRecoveryHostedInvoice(user, id).getUrl())); }
        catch (BillingDomainException error) { return mapBillingException(error); }
    }

    @PostMapping("/recoveries/{id}/reconcile")
    public Result<com.studyagent.service.domain.billing.PaymentRecoverySummary> reconcileRecovery(
            @PathVariable String id,
            @RequestAttribute(value = "clerkUserId", required = false) String user) {
        if (user == null || user.isBlank()) return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        try { return Result.success(billingDomainService.reconcilePaymentRecovery(user, id)); }
        catch (BillingDomainException error) { return mapBillingException(error); }
    }

    @PostMapping("/recoveries/{id}/replacement/exit")
    public Result<com.studyagent.service.domain.billing.PaymentRecoverySummary> exitReplacement(
            @PathVariable String id, @RequestBody RecoveryReplacementExitRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String user) {
        if (user == null || user.isBlank()) return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        if (request == null || request.getOrderNo() == null || request.getOrderNo().isBlank()) return Result.error(ApiCode.PARAM_ERROR);
        try { return Result.success(billingDomainService.exitRecoveryReplacement(user, id, request.getOrderNo())); }
        catch (BillingDomainException error) { return mapBillingException(error); }
    }

    private <T> Result<T> mapBillingException(BillingDomainException e) {
        if (e.getCode().startsWith("PRICING_")) return PricingErrors.result(e);
        return switch (e.getCode()) {
            case "STRIPE_NOT_CONFIGURED" -> Result.error(ApiCode.STRIPE_NOT_CONFIGURED);
            case "STRIPE_CUSTOMER_NOT_FOUND" -> Result.error(ApiCode.BILLING_CUSTOMER_NOT_FOUND);
            case "BILLING_RECORD_NOT_FOUND" -> Result.error(ApiCode.BILLING_RECORD_NOT_FOUND);
            case "BILLING_INVOICE_NOT_AVAILABLE" -> Result.error(ApiCode.BILLING_INVOICE_NOT_AVAILABLE);
            case "PAYMENT_PROCESSING", "RECOVERY_STALE", "RECOVERY_ACTION_PENDING" -> Result.error(ApiCode.PAYMENT_PROCESSING);
            case "INVALID_PORTAL_PURPOSE" -> Result.error(ApiCode.PARAM_ERROR, e.getMessage());
            case "INVALID_BILLING_RECORD_CURSOR" -> Result.error(ApiCode.PARAM_ERROR, e.getMessage());
            case "INVALID_RETURN_URL" -> Result.error(ApiCode.INVALID_CHECKOUT_RETURN_URL, e.getMessage());
            case "STRIPE_ERROR" -> Result.error(ApiCode.STRIPE_API_ERROR, e.getMessage());
            default -> Result.error(ApiCode.INTERNAL_ERROR, e.getMessage());
        };
    }

    @Data
    static class BillingPortalSessionRequest {
        private String returnUrl;
        private String purpose;
    }

    @Data
    static class RecoveryReplacementExitRequest {
        private String orderNo;
    }
}
