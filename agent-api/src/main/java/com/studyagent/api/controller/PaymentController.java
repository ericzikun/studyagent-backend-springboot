package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.common.analytics.AnalyticsEvents;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.common.api.ApiCode;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.payment.*;
import com.studyagent.service.domain.user.ClerkClient;
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
    private final BillingDomainService billingDomainService;
    private final AnalyticsService analyticsService;

    @PostMapping("/subscription-checkout")
    public Result<Map<String, Object>> createSubscriptionCheckout(
            @RequestBody SubscriptionCheckoutRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId,
            @RequestAttribute(value = "userInfo", required = false) ClerkClient.UserInfo userInfo) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        try {
            CheckoutSessionResult checkout = billingDomainService.createSubscriptionCheckout(
                    clerkUserId,
                    userInfo == null ? null : userInfo.email,
                    request.getPlanCode(),
                    request.getSuccessUrl(),
                    request.getCancelUrl(),
                    request.getResumeToken());
            captureCheckoutSessionCreated(
                    clerkUserId,
                    request.getPlanCode(),
                    "subscription",
                    userInfo == null ? null : userInfo.email,
                    checkout
            );
            return Result.success(toCheckoutData(checkout));
        } catch (BillingDomainException e) {
            captureCheckoutSessionFailed(
                    clerkUserId,
                    request.getPlanCode(),
                    "subscription",
                    userInfo == null ? null : userInfo.email,
                    e.getCode(),
                    e.getMessage()
            );
            return mapBillingException(e);
        } catch (Exception e) {
            log.error("创建订阅支付会话失败: {}", e.getMessage(), e);
            captureCheckoutSessionFailed(
                    clerkUserId,
                    request.getPlanCode(),
                    "subscription",
                    userInfo == null ? null : userInfo.email,
                    "UNKNOWN",
                    e.getMessage()
            );
            return Result.error(ApiCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @PostMapping("/addon-checkout")
    public Result<Map<String, Object>> createAddonCheckout(
            @RequestBody AddonCheckoutRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId,
            @RequestAttribute(value = "userInfo", required = false) ClerkClient.UserInfo userInfo) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        try {
            CheckoutSessionResult checkout = billingDomainService.createAddonCheckout(
                    clerkUserId,
                    userInfo == null ? null : userInfo.email,
                    request.getAddonCode(),
                    request.getSuccessUrl(),
                    request.getCancelUrl(),
                    request.getResumeToken());
            captureCheckoutSessionCreated(
                    clerkUserId,
                    request.getAddonCode(),
                    "addon",
                    userInfo == null ? null : userInfo.email,
                    checkout
            );
            return Result.success(toCheckoutData(checkout));
        } catch (BillingDomainException e) {
            captureCheckoutSessionFailed(
                    clerkUserId,
                    request.getAddonCode(),
                    "addon",
                    userInfo == null ? null : userInfo.email,
                    e.getCode(),
                    e.getMessage()
            );
            return mapBillingException(e);
        } catch (Exception e) {
            log.error("创建加购支付会话失败: {}", e.getMessage(), e);
            captureCheckoutSessionFailed(
                    clerkUserId,
                    request.getAddonCode(),
                    "addon",
                    userInfo == null ? null : userInfo.email,
                    "UNKNOWN",
                    e.getMessage()
            );
            return Result.error(ApiCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @PostMapping("/create-checkout-session")
    public Result<Map<String, Object>> createCheckoutSession(
            @RequestBody CreateCheckoutSessionRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId,
            @RequestAttribute(value = "userInfo", required = false) ClerkClient.UserInfo userInfo) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        if (request.getClerkUserId() != null
                && !request.getClerkUserId().isBlank()
                && !clerkUserId.equals(request.getClerkUserId())) {
            return Result.error(ApiCode.NO_PERMISSION);
        }
        String customerEmail = userInfo == null ? null : userInfo.email;
        try {
            CreateCheckoutSessionCommand command = CreateCheckoutSessionCommand.builder()
                    .clerkUserId(clerkUserId)
                    .customerEmail(customerEmail)
                    .packageType(request.getPackageType())
                    .successUrl(request.getSuccessUrl())
                    .cancelUrl(request.getCancelUrl())
                    .build();

            CheckoutSessionResult result = paymentDomainService.createCheckoutSession(command);

            // 埋点：支付会话创建成功
            Map<String, Object> paymentProps = buildCheckoutAnalyticsProps(
                    request.getPackageType(),
                    request.getPackageType(),
                    customerEmail,
                    result.getSessionId(),
                    result.getCheckoutKind()
            );
            analyticsService.capture(clerkUserId, AnalyticsEvents.PAYMENT_SESSION_CREATED, paymentProps);
            analyticsService.capture(clerkUserId, AnalyticsEvents.BILLING_CHECKOUT_SESSION_CREATED, paymentProps);

            Map<String, Object> data = new HashMap<>();
            data.put("sessionId", result.getSessionId());
            data.put("checkoutUrl", result.getCheckoutUrl());
            data.put("expiresAt", result.getExpiresAt());
            return Result.success(data);
        } catch (PaymentDomainException e) {
            // 埋点：支付会话创建失败
            Map<String, Object> errorProps = buildCheckoutAnalyticsProps(
                    request.getPackageType(),
                    request.getPackageType(),
                    customerEmail,
                    null,
                    null
            );
            errorProps.put("error_code", e.getCode());
            errorProps.put("error_message", e.getMessage());
            analyticsService.capture(clerkUserId, AnalyticsEvents.PAYMENT_SESSION_FAILED, errorProps);
            analyticsService.capture(clerkUserId, AnalyticsEvents.BILLING_CHECKOUT_SESSION_FAILED, errorProps);

            if ("STRIPE_ERROR".equals(e.getCode()) && e.getCause() instanceof com.stripe.exception.StripeException) {
                log.error("Stripe API 错误: {}", e.getMessage(), e);
                return Result.error(ApiCode.STRIPE_API_ERROR, e.getMessage());
            }
            return mapDomainException(e);
        } catch (Exception e) {
            log.error("创建支付会话失败: {}", e.getMessage(), e);

            // 埋点：支付会话创建失败（未知错误）
            Map<String, Object> errorProps = buildCheckoutAnalyticsProps(
                    request.getPackageType(),
                    request.getPackageType(),
                    customerEmail,
                    null,
                    null
            );
            errorProps.put("error_code", "UNKNOWN");
            errorProps.put("error_message", e.getMessage());
            analyticsService.capture(clerkUserId, AnalyticsEvents.PAYMENT_SESSION_FAILED, errorProps);
            analyticsService.capture(clerkUserId, AnalyticsEvents.BILLING_CHECKOUT_SESSION_FAILED, errorProps);

            return Result.error(ApiCode.PAYMENT_SESSION_CREATE_FAILED);
        }
    }

    @GetMapping("/session-status")
    public Result<Map<String, Object>> getSessionStatus(
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        if (sessionId == null || sessionId.isEmpty()) {
            return Result.error(ApiCode.SESSION_ID_REQUIRED);
        }
        try {
            SessionStatusResult result = paymentDomainService.getSessionStatus(sessionId);
            if (result.getClerkUserId() == null || !clerkUserId.equals(result.getClerkUserId())) {
                return Result.error(ApiCode.NO_PERMISSION);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("sessionId", result.getSessionId());
            data.put("status", result.getStatus());
            data.put("paymentStatus", result.getPaymentStatus());
            data.put("amountTotal", result.getAmountTotal());
            data.put("currency", result.getCurrency());
            data.put("createdAt", result.getCreatedAt());
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
        var catalog = billingDomainService.getCatalog();
        Map<String, Object> data = new HashMap<>();
        data.put("stripePublishableKey", result.getStripePublishableKey());
        data.put("packages", result.getPackages());
        data.put("plans", catalog.getPlans());
        data.put("addons", catalog.getAddons());
        return Result.success(data);
    }

    private Map<String, Object> toCheckoutData(CheckoutSessionResult checkout) {
        Map<String, Object> data = new HashMap<>();
        data.put("checkoutKind", checkout.getCheckoutKind());
        data.put("sessionId", checkout.getSessionId());
        data.put("referenceId", checkout.getReferenceId());
        data.put("checkoutUrl", checkout.getCheckoutUrl());
        data.put("expiresAt", checkout.getExpiresAt());
        data.put("resumeToken", checkout.getResumeToken());
        data.put("quotedAmountCents", checkout.getQuotedAmountCents());
        data.put("upgradeChargeType", checkout.getUpgradeChargeType());
        data.put("targetPlanCode", checkout.getTargetPlanCode());
        return data;
    }

    private Result<Map<String, Object>> mapBillingException(BillingDomainException e) {
        return switch (e.getCode()) {
            case "STRIPE_NOT_CONFIGURED" -> Result.error(ApiCode.STRIPE_NOT_CONFIGURED);
            case "INVALID_PLAN", "PLAN_PRICE_NOT_CONFIGURED" -> Result.error(ApiCode.INVALID_PLAN, e.getMessage());
            case "INVALID_ADDON", "ADDON_PRICE_NOT_CONFIGURED" -> Result.error(ApiCode.INVALID_ADDON, e.getMessage());
            case "ADDON_REQUIRES_PAID_MEMBER" -> Result.error(ApiCode.ADDON_REQUIRES_PAID_MEMBER);
            case "SUBSCRIPTION_ALREADY_EXISTS" -> Result.error(ApiCode.SUBSCRIPTION_ALREADY_EXISTS);
            case "SUBSCRIPTION_CHANGE_PENDING" -> Result.error(ApiCode.SUBSCRIPTION_CHANGE_PENDING);
            case "PAYMENT_RESOLUTION_REQUIRED" -> Result.error(ApiCode.PAYMENT_RESOLUTION_REQUIRED);
            case "SUBSCRIPTION_NOT_FOUND" -> Result.error(ApiCode.SUBSCRIPTION_NOT_FOUND);
            case "INVALID_RETURN_URL" -> Result.error(ApiCode.INVALID_CHECKOUT_RETURN_URL, e.getMessage());
            case "INVALID_UPGRADE_TARGET", "INVALID_DOWNGRADE_TARGET", "INVALID_SUBSCRIPTION_ITEMS",
                    "SUBSCRIPTION_STATE_INVALID" ->
                    Result.error(ApiCode.SUBSCRIPTION_STATE_INVALID);
            case "TRIAL_ALREADY_USED" -> Result.error(ApiCode.INTRO_TRIAL_ALREADY_USED);
            case "BASIC_REQUIRES_TRIAL" -> Result.error(ApiCode.BASIC_REQUIRES_TRIAL);
            case "STRIPE_ERROR" -> Result.error(ApiCode.STRIPE_API_ERROR, e.getMessage());
            default -> Result.error(ApiCode.INTERNAL_ERROR, e.getMessage());
        };
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
            case "SESSION_OWNER_MISMATCH" -> Result.error(ApiCode.NO_PERMISSION);
            case "INTERNAL_ERROR" -> Result.error(ApiCode.INTERNAL_ERROR, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            case "STRIPE_ERROR" -> Result.error(ApiCode.STRIPE_API_ERROR, e.getMessage());
            default -> Result.error(e.getMessage());
        };
    }

    private void captureCheckoutSessionCreated(
            String clerkUserId,
            String planId,
            String packageType,
            String customerEmail,
            CheckoutSessionResult checkout
    ) {
        Map<String, Object> paymentProps = buildCheckoutAnalyticsProps(
                planId,
                packageType,
                customerEmail,
                checkout.getSessionId(),
                checkout.getCheckoutKind()
        );
        analyticsService.capture(clerkUserId, AnalyticsEvents.PAYMENT_SESSION_CREATED, paymentProps);
        analyticsService.capture(clerkUserId, AnalyticsEvents.BILLING_CHECKOUT_SESSION_CREATED, paymentProps);
    }

    private void captureCheckoutSessionFailed(
            String clerkUserId,
            String planId,
            String packageType,
            String customerEmail,
            String errorCode,
            String errorMessage
    ) {
        Map<String, Object> errorProps = buildCheckoutAnalyticsProps(
                planId,
                packageType,
                customerEmail,
                null,
                null
        );
        errorProps.put("error_code", errorCode);
        errorProps.put("error_message", errorMessage);
        analyticsService.capture(clerkUserId, AnalyticsEvents.PAYMENT_SESSION_FAILED, errorProps);
        analyticsService.capture(clerkUserId, AnalyticsEvents.BILLING_CHECKOUT_SESSION_FAILED, errorProps);
    }

    private Map<String, Object> buildCheckoutAnalyticsProps(
            String planId,
            String packageType,
            String customerEmail,
            String sessionId,
            String checkoutKind
    ) {
        Map<String, Object> props = new HashMap<>();
        props.put("package_type", packageType);
        props.put("plan_id", planId);
        props.put("customer_email", customerEmail);
        props.put("session_id", sessionId);
        props.put("checkout_session_id", sessionId);
        props.put("checkout_kind", checkoutKind);
        return props;
    }

    @Data
    static class CreateCheckoutSessionRequest {
        private String clerkUserId;
        private String customerEmail;
        private String packageType;
        private String successUrl;
        private String cancelUrl;
    }

    @Data
    static class SubscriptionCheckoutRequest {
        private String planCode;
        private String successUrl;
        private String cancelUrl;
        private String resumeToken;
    }

    @Data
    static class AddonCheckoutRequest {
        private String addonCode;
        private String successUrl;
        private String cancelUrl;
        private String resumeToken;
    }
}
