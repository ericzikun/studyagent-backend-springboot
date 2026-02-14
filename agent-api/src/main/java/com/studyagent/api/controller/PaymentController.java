package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.checkout.Session;
import com.stripe.param.PriceListParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付控制器
 * 参考 Python 后端实现，支持 Stripe Checkout Session 创建和查询
 */
@Slf4j
@RestController
@RequestMapping("/v1/payment")
@RequiredArgsConstructor
public class PaymentController {
    
    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;
    
    @Value("${stripe.publishable-key:}")
    private String stripePublishableKey;
    
    @Value("${stripe.price.starter:}")
    private String priceStarter;
    
    @Value("${stripe.price.pro:}")
    private String pricePro;
    
    @Value("${stripe.price.academic:}")
    private String priceAcademic;
    
    @Value("${payment.success-url:http://localhost:3000/success}")
    private String successUrl;
    
    @Value("${payment.cancel-url:http://localhost:3000/cancel}")
    private String cancelUrl;
    
    @PostConstruct
    public void init() {
        if (stripeSecretKey != null && !stripeSecretKey.isEmpty()) {
            Stripe.apiKey = stripeSecretKey;
        }
    }
    
    @PostMapping("/create-checkout-session")
    public Result<Map<String, Object>> createCheckoutSession(
            @RequestBody CreateCheckoutSessionRequest request) {
        try {
            // 检查 Stripe 配置
            if (stripeSecretKey == null || stripeSecretKey.isEmpty()) {
                log.error("Stripe Secret Key 未配置");
                return Result.error(ApiCode.STRIPE_NOT_CONFIGURED);
            }
            
            // 获取套餐配置
            String configuredPriceId = getPriceId(request.getPackageType());
            if (configuredPriceId == null || configuredPriceId.isEmpty()) {
                log.error("无效的套餐类型: {}", request.getPackageType());
                return Result.error(ApiCode.INVALID_PACKAGE_TYPE, request.getPackageType());
            }
            
            // 验证配置值：必须是 Price ID (price_...) 或 Product ID (prod_...)，不能是数字价格
            if (!configuredPriceId.startsWith("price_") && !configuredPriceId.startsWith("prod_")) {
                log.error("配置错误: {} 套餐的 Price ID 配置无效: {}。应该是 Stripe Price ID (price_xxxxx) 或 Product ID (prod_xxxxx)，不能是数字价格", 
                    request.getPackageType(), configuredPriceId);
                return Result.error(ApiCode.PRICE_CONFIG_ERROR, 
                    getPackageName(request.getPackageType()), 
                    request.getPackageType().toUpperCase());
            }
            
            // 如果配置的是 Product ID (prod_...)，尝试查找其下的第一个 Price
            String targetPriceId = configuredPriceId;
            if (configuredPriceId.startsWith("prod_")) {
                log.info("检测到 Product ID，查找其下的第一个 Price: {}", configuredPriceId);
                try {
                    PriceListParams priceListParams = PriceListParams.builder()
                        .setProduct(configuredPriceId)
                        .setActive(true)
                        .setLimit(1L)
                        .build();
                    
                    com.stripe.model.PriceCollection prices = Price.list(priceListParams);
                    if (prices.getData() != null && !prices.getData().isEmpty()) {
                        targetPriceId = prices.getData().get(0).getId();
                        log.info("找到 Price ID: {}", targetPriceId);
                    } else {
                        log.error("产品 {} 下没有找到有效的价格 ID", configuredPriceId);
                        return Result.error(ApiCode.PRICE_NOT_FOUND, configuredPriceId);
                    }
                } catch (StripeException e) {
                    log.error("查找 Price 失败: {}", e.getMessage());
                    return Result.error(ApiCode.INTERNAL_ERROR, "Find price failed: " + e.getMessage());
                }
            }
            
            // 使用回调地址（如果请求中提供，否则使用配置的默认值）
            String finalSuccessUrl = request.getSuccessUrl() != null && !request.getSuccessUrl().isEmpty()
                ? request.getSuccessUrl() : successUrl;
            String finalCancelUrl = request.getCancelUrl() != null && !request.getCancelUrl().isEmpty()
                ? request.getCancelUrl() : cancelUrl;
            
            // 添加 session_id 占位符，Stripe 会自动替换
            if (!finalSuccessUrl.contains("{CHECKOUT_SESSION_ID}")) {
                finalSuccessUrl = finalSuccessUrl + (finalSuccessUrl.contains("?") ? "&" : "?") + "session_id={CHECKOUT_SESSION_ID}";
            }
            
            // 创建 Stripe Checkout Session
            SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(finalSuccessUrl)
                .setCancelUrl(finalCancelUrl)
                .addLineItem(
                    SessionCreateParams.LineItem.builder()
                        .setPrice(targetPriceId)
                        .setQuantity(1L)
                        .build()
                )
                .setCustomerEmail(request.getCustomerEmail())
                .putMetadata("package_type", request.getPackageType())
                .putMetadata("clerk_user_id", request.getClerkUserId() != null ? request.getClerkUserId() : "")
                .putMetadata("credits", String.valueOf(getCredits(request.getPackageType())))
                .build();
            
            Session session = Session.create(params);
            
            log.info("创建支付会话成功: session_id={}, email={}, package={}, clerk_user_id={}", 
                session.getId(), request.getCustomerEmail(), request.getPackageType(), request.getClerkUserId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("sessionId", session.getId());
            response.put("checkoutUrl", session.getUrl());
            response.put("expiresAt", session.getExpiresAt());
            
            return Result.success(response);
        } catch (StripeException e) {
            log.error("Stripe API 错误: {}", e.getMessage(), e);
            return Result.error(ApiCode.STRIPE_API_ERROR, e.getMessage());
        } catch (Exception e) {
            log.error("创建支付会话失败: {}", e.getMessage(), e);
            return Result.error(ApiCode.PAYMENT_SESSION_CREATE_FAILED);
        }
    }
    
    @GetMapping("/session-status")
    public Result<Map<String, Object>> getSessionStatus(
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        // 兼容两种参数名：session_id 和 sessionId（向后兼容）
        if (sessionId == null || sessionId.isEmpty()) {
            return Result.error(ApiCode.SESSION_ID_REQUIRED);
        }
        try {
            Session session = Session.retrieve(sessionId);
            
            Map<String, Object> data = new HashMap<>();
            data.put("sessionId", session.getId());
            data.put("status", session.getStatus());
            data.put("paymentStatus", session.getPaymentStatus());
            data.put("amountTotal", session.getAmountTotal());
            data.put("currency", session.getCurrency());
            data.put("customerEmail", session.getCustomerDetails() != null 
                ? session.getCustomerDetails().getEmail() : null);
            data.put("createdAt", session.getCreated());
            data.put("clerkUserId", session.getMetadata() != null 
                ? session.getMetadata().get("clerk_user_id") : null);
            
            return Result.success(data);
        } catch (StripeException e) {
            return Result.error(ApiCode.SESSION_QUERY_FAILED, e.getMessage());
        }
    }
    
    @GetMapping("/config")
    public Result<Map<String, Object>> getPaymentConfig() {
        Map<String, Object> data = new HashMap<>();
        data.put("stripePublishableKey", stripePublishableKey);
        
        // 返回数组格式，使用驼峰命名
        List<Map<String, Object>> packages = new ArrayList<>();
        packages.add(Map.of(
            "type", "starter",
            "name", "Starter Pack",
            "credits", 1,
            "priceId", priceStarter != null ? priceStarter : ""
        ));
        packages.add(Map.of(
            "type", "pro",
            "name", "Pro Pack",
            "credits", 10,
            "priceId", pricePro != null ? pricePro : ""
        ));
        packages.add(Map.of(
            "type", "academic",
            "name", "Academic Pack",
            "credits", 50,
            "priceId", priceAcademic != null ? priceAcademic : ""
        ));
        
        data.put("packages", packages);
        
        return Result.success(data);
    }
    
    private String getPriceId(String packageType) {
        return switch (packageType) {
            case "starter" -> priceStarter;
            case "pro" -> pricePro;
            case "academic" -> priceAcademic;
            default -> null;
        };
    }
    
    private int getCredits(String packageType) {
        return switch (packageType) {
            case "starter" -> 1;
            case "pro" -> 10;
            case "academic" -> 50;
            default -> 0;
        };
    }
    
    private String getPackageName(String packageType) {
        return switch (packageType) {
            case "starter" -> "Starter";
            case "pro" -> "Pro";
            case "academic" -> "Academic";
            default -> packageType;
        };
    }
    
    @Data
    static class CreateCheckoutSessionRequest {
        private String clerkUserId;
        private String customerEmail;
        private String packageType;
        private String successUrl;  // 可选，支付成功回调地址
        private String cancelUrl;    // 可选，支付取消回调地址
    }
}

