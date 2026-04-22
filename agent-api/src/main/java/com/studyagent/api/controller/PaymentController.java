package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.common.analytics.AnalyticsEvents;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.common.api.ApiCode;
import com.studyagent.service.domain.payment.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付控制器
 * 支持 Stripe Checkout Session 创建和查询
 */
@Slf4j
@RestController
@RequestMapping("/v1/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentDomainService paymentDomainService;
    private final AnalyticsService analyticsService;

    @PostMapping("/create-checkout-session")
    public Result<Map<String, Object>> createCheckoutSession(
            @RequestBody CreateCheckoutSessionRequest request) {
        try {
            CreateCheckoutSessionCommand command = CreateCheckoutSessionCommand.builder()
                    .clerkUserId(request.getClerkUserId())
                    .customerEmail(request.getCustomerEmail())
                    .packageType(request.getPackageType())
                    .successUrl(request.getSuccessUrl())
                    .cancelUrl(request.getCancelUrl())
                    .build();

            CheckoutSessionResult result = paymentDomainService.createCheckoutSession(command);

            // 埋点：支付会话创建成功
            Map<String, Object> paymentProps = new HashMap<>();
            paymentProps.put("package_type", request.getPackageType());
            paymentProps.put("customer_email", request.getCustomerEmail());
            paymentProps.put("session_id", result.getSessionId());
            analyticsService.capture(request.getClerkUserId(), AnalyticsEvents.PAYMENT_SESSION_CREATED, paymentProps);

            Map<String, Object> data = new HashMap<>();
            data.put("sessionId", result.getSessionId());
            data.put("checkoutUrl", result.getCheckoutUrl());
            data.put("expiresAt", result.getExpiresAt());
            return Result.success(data);
        } catch (PaymentDomainException e) {
            // 埋点：支付会话创建失败
            Map<String, Object> errorProps = new HashMap<>();
            errorProps.put("package_type", request.getPackageType());
            errorProps.put("error_code", e.getCode());
            errorProps.put("error_message", e.getMessage());
            analyticsService.capture(request.getClerkUserId(), AnalyticsEvents.PAYMENT_SESSION_FAILED, errorProps);

            if ("STRIPE_ERROR".equals(e.getCode()) && e.getCause() instanceof com.stripe.exception.StripeException) {
                log.error("Stripe API 错误: {}", e.getMessage(), e);
                return Result.error(ApiCode.STRIPE_API_ERROR, e.getMessage());
            }
            return mapDomainException(e);
        } catch (Exception e) {
            log.error("创建支付会话失败: {}", e.getMessage(), e);

            // 埋点：支付会话创建失败（未知错误）
            Map<String, Object> errorProps = new HashMap<>();
            errorProps.put("package_type", request.getPackageType());
            errorProps.put("error_code", "UNKNOWN");
            errorProps.put("error_message", e.getMessage());
            analyticsService.capture(request.getClerkUserId(), AnalyticsEvents.PAYMENT_SESSION_FAILED, errorProps);

            return Result.error(ApiCode.PAYMENT_SESSION_CREATE_FAILED);
        }
    }

    @GetMapping("/session-status")
    public Result<Map<String, Object>> getSessionStatus(
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Result.error(ApiCode.SESSION_ID_REQUIRED);
        }
        try {
            SessionStatusResult result = paymentDomainService.getSessionStatus(sessionId);

            Map<String, Object> data = new HashMap<>();
            data.put("sessionId", result.getSessionId());
            data.put("status", result.getStatus());
            data.put("paymentStatus", result.getPaymentStatus());
            data.put("amountTotal", result.getAmountTotal());
            data.put("currency", result.getCurrency());
            data.put("customerEmail", result.getCustomerEmail());
            data.put("createdAt", result.getCreatedAt());
            data.put("clerkUserId", result.getClerkUserId());
            return Result.success(data);
        } catch (PaymentDomainException e) {
            if ("STRIPE_ERROR".equals(e.getCode())) {
                return Result.error(ApiCode.SESSION_QUERY_FAILED, e.getMessage());
            }
            return mapDomainException(e);
        } catch (Exception e) {
            log.error("查询会话失败: {}", e.getMessage());
            return Result.error(ApiCode.SESSION_QUERY_FAILED, e.getMessage());
        }
    }

    @GetMapping("/config")
    public Result<Map<String, Object>> getPaymentConfig() {
        PaymentConfigResult result = paymentDomainService.getPaymentConfig();
        Map<String, Object> data = new HashMap<>();
        data.put("stripePublishableKey", result.getStripePublishableKey());
        data.put("packages", result.getPackages());
        return Result.success(data);
    }

    private Result<Map<String, Object>> mapDomainException(PaymentDomainException e) {
        Object[] args = e.getFormatArgs();
        return switch (e.getCode()) {
            case "STRIPE_NOT_CONFIGURED" -> Result.error(ApiCode.STRIPE_NOT_CONFIGURED);
            case "INVALID_PACKAGE_TYPE" -> Result.error(ApiCode.INVALID_PACKAGE_TYPE, args != null && args.length > 0 ? args[0] : e.getMessage());
            case "PRICE_CONFIG_ERROR" -> args != null && args.length >= 2
                    ? Result.error(ApiCode.PRICE_CONFIG_ERROR, args[0], args[1])
                    : Result.error(ApiCode.PRICE_CONFIG_ERROR, e.getMessage());
            case "PRICE_NOT_FOUND" -> Result.error(ApiCode.PRICE_NOT_FOUND, args != null && args.length > 0 ? args[0] : e.getMessage());
            case "INTERNAL_ERROR" -> Result.error(ApiCode.INTERNAL_ERROR, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            case "STRIPE_ERROR" -> Result.error(ApiCode.STRIPE_API_ERROR, e.getMessage());
            default -> Result.error(e.getMessage());
        };
    }

    @Data
    static class CreateCheckoutSessionRequest {
        private String clerkUserId;
        private String customerEmail;
        private String packageType;
        private String successUrl;
        private String cancelUrl;
    }
}
