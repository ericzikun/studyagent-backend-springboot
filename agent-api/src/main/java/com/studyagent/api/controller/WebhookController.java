package com.studyagent.api.controller;

import com.studyagent.common.analytics.AnalyticsEvents;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.common.quota.FeatureCode;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.studyagent.infra.service.QuotaRechargeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Webhook控制器
 * 处理 Stripe 支付回调，完成用户充值到账
 */
@Slf4j
@RestController
@RequestMapping("/v1/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final QuotaRechargeService quotaRechargeService;
    private final AnalyticsService analyticsService;

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    @PostMapping("/stripe")
    public ResponseEntity<Map<String, String>> stripeWebhook(
            @RequestBody String payload,
            @RequestHeader("stripe-signature") String signature) {
        try {
            // 验证 Webhook 签名
            Event event;
            if (webhookSecret != null && !webhookSecret.isEmpty()) {
                event = Webhook.constructEvent(payload, signature, webhookSecret);
            } else {
                log.warn("Webhook secret未配置，跳过签名验证（仅开发环境）");
                // 仅开发环境，直接解析
                com.google.gson.Gson gson = new com.google.gson.Gson();
                event = gson.fromJson(payload, Event.class);
            }

            log.info("收到 Stripe Webhook: event_type={}, event_id={}", event.getType(), event.getId());

            // 处理事件
            if ("checkout.session.completed".equals(event.getType())) {
                Session session = resolveSession(event);
                if (session != null) {
                    handleCheckoutSessionCompleted(session);
                } else {
                    log.error("无法解析 checkout.session，event_id={}", event.getId());
                }
            }

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (SignatureVerificationException e) {
            log.error("Webhook签名验证失败", e);
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            log.error("处理Webhook失败", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 解析 Session 对象。当 getObject() 为空时（API 版本不匹配），使用 deserializeUnsafe 强制反序列化。
     */
    private Session resolveSession(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        if (deserializer.getObject().isPresent()) {
            StripeObject obj = deserializer.getObject().get();
            return obj instanceof Session ? (Session) obj : null;
        }
        try {
            StripeObject obj = deserializer.deserializeUnsafe();
            return obj instanceof Session ? (Session) obj : null;
        } catch (Exception e) {
            log.warn("deserializeUnsafe 失败，尝试用 getRawJson 手动解析: {}", e.getMessage());
            String raw = deserializer.getRawJson();
            if (raw != null && !raw.isEmpty()) {
                return com.stripe.net.ApiResource.GSON.fromJson(raw, Session.class);
            }
            return null;
        }
    }

    private void handleCheckoutSessionCompleted(Session session) {
        Map<String, String> metadata = session.getMetadata();
        String clerkUserId = metadata != null ? metadata.get("clerk_user_id") : null;
        String packageType = metadata != null ? metadata.get("package_type") : null;
        String creditsStr = metadata != null ? metadata.get("credits") : null;
        String featureCode = metadata != null ? metadata.get("feature_code") : null;
        if (featureCode == null || featureCode.isEmpty()) {
            featureCode = FeatureCode.TASK_CREATE.getCode();
        }

        log.info("支付完成！Session ID: {}, 客户邮箱: {}, 支付金额: ${}, 套餐类型: {}, 功能: {}, 获得积分: {}, 用户ID: {}",
            session.getId(),
            session.getCustomerDetails() != null ? session.getCustomerDetails().getEmail() : null,
            session.getAmountTotal() != null ? session.getAmountTotal() / 100.0 : 0,
            packageType,
            featureCode,
            creditsStr,
            clerkUserId
        );

        // 解析额度：优先用 metadata.credits，否则从套餐表获取
        long quotaAmount;
        try {
            quotaAmount = creditsStr != null && !creditsStr.isEmpty()
                ? Long.parseLong(creditsStr)
                : 0L;
        } catch (NumberFormatException e) {
            quotaAmount = 0L;
        }
        if (quotaAmount <= 0 && packageType != null) {
            Long fromPackage = quotaRechargeService.getQuotaAmountFromPackage(featureCode, packageType);
            if (fromPackage != null) {
                quotaAmount = fromPackage;
            }
        }

        int priceCents = session.getAmountTotal() != null ? session.getAmountTotal().intValue() : 0;
        String currency = session.getCurrency() != null ? session.getCurrency() : "usd";
        String paymentIntentId = session.getPaymentIntent();
        String customerEmail = session.getCustomerDetails() != null ? session.getCustomerDetails().getEmail() : null;

        if (quotaAmount > 0 && clerkUserId != null && !clerkUserId.isEmpty()) {
            boolean success = quotaRechargeService.processRecharge(
                clerkUserId,
                featureCode,
                packageType != null ? packageType : "unknown",
                quotaAmount,
                priceCents,
                currency,
                session.getId(),
                paymentIntentId
            );

            // 埋点：支付完成（无论是否幂等跳过，都记录支付完成事件）
            if (success) {
                Map<String, Object> paymentProps = new HashMap<>();
                paymentProps.put("session_id", session.getId());
                paymentProps.put("package_type", packageType);
                paymentProps.put("feature_code", featureCode);
                paymentProps.put("quota_amount", quotaAmount);
                paymentProps.put("price_cents", priceCents);
                paymentProps.put("currency", currency);
                paymentProps.put("customer_email", customerEmail);
                analyticsService.capture(clerkUserId, AnalyticsEvents.PAYMENT_COMPLETED, paymentProps);

                // 埋点：充值成功（积分到账）
                Map<String, Object> rechargeProps = new HashMap<>();
                rechargeProps.put("order_no", session.getId());
                rechargeProps.put("package_code", packageType);
                rechargeProps.put("quota_amount", quotaAmount);
                rechargeProps.put("price_cents", priceCents);
                rechargeProps.put("currency", currency);
                analyticsService.capture(clerkUserId, AnalyticsEvents.RECHARGE_SUCCESS, rechargeProps);
            }
        } else {
            log.warn("支付回调跳过充值: clerk_user_id 为空或 quota_amount 无效");
        }
    }
}

