package com.studyagent.api.controller;

import com.studyagent.common.analytics.AnalyticsEvents;
import com.studyagent.common.analytics.AnalyticsService;
import com.studyagent.common.quota.FeatureCode;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.studyagent.infra.service.QuotaRechargeService;
import com.studyagent.api.service.robot.RobotNotifyAsyncService;
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
    private final RobotNotifyAsyncService robotNotifyAsyncService;

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
                event = new com.google.gson.Gson().fromJson(payload, Event.class);
            }

            log.info("收到 Stripe Webhook: event_type={}, event_id={}", event.getType(), event.getId());

            // 处理事件
            if ("checkout.session.completed".equals(event.getType())) {
                Session session = resolveSession(event);
                if (session != null) {
                    handleCheckoutSessionCompleted(session, event.getId());
                } else {
                    log.error("无法解析 checkout.session，event_id={}", event.getId());
                }
            } else if ("checkout.session.expired".equals(event.getType())) {
                Session session = resolveSession(event);
                if (session != null) {
                    handleCheckoutSessionExpired(session, event.getId());
                } else {
                    log.error("无法解析 checkout.session.expired，event_id={}", event.getId());
                }
            } else if ("charge.failed".equals(event.getType())) {
                Charge charge = resolveCharge(event);
                if (charge != null) {
                    handleChargeFailed(charge, event.getType());
                } else {
                    log.error("无法解析 charge，event_id={}", event.getId());
                }
            } else if ("payment_intent.payment_failed".equals(event.getType())) {
                PaymentIntent paymentIntent = resolvePaymentIntent(event);
                if (paymentIntent != null) {
                    handlePaymentIntentFailed(paymentIntent, event.getType());
                } else {
                    log.error("无法解析 payment_intent，event_id={}", event.getId());
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
     * 解析 Charge 对象
     */
    private Charge resolveCharge(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        if (deserializer.getObject().isPresent()) {
            StripeObject obj = deserializer.getObject().get();
            return obj instanceof Charge ? (Charge) obj : null;
        }
        try {
            StripeObject obj = deserializer.deserializeUnsafe();
            return obj instanceof Charge ? (Charge) obj : null;
        } catch (Exception e) {
            log.warn("deserializeUnsafe 失败，尝试用 getRawJson 手动解析: {}", e.getMessage());
            String raw = deserializer.getRawJson();
            if (raw != null && !raw.isEmpty()) {
                return com.stripe.net.ApiResource.GSON.fromJson(raw, Charge.class);
            }
            return null;
        }
    }

    /**
     * 解析 PaymentIntent 对象
     */
    private PaymentIntent resolvePaymentIntent(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        if (deserializer.getObject().isPresent()) {
            StripeObject obj = deserializer.getObject().get();
            return obj instanceof PaymentIntent ? (PaymentIntent) obj : null;
        }
        try {
            StripeObject obj = deserializer.deserializeUnsafe();
            return obj instanceof PaymentIntent ? (PaymentIntent) obj : null;
        } catch (Exception e) {
            log.warn("deserializeUnsafe 失败，尝试用 getRawJson 手动解析: {}", e.getMessage());
            String raw = deserializer.getRawJson();
            if (raw != null && !raw.isEmpty()) {
                return com.stripe.net.ApiResource.GSON.fromJson(raw, PaymentIntent.class);
            }
            return null;
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

    private void handleCheckoutSessionCompleted(Session session, String stripeEventId) {
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
                analyticsService.capture(clerkUserId, AnalyticsEvents.BILLING_PAYMENT_SUCCEEDED, paymentProps);

                // 埋点：充值成功（积分到账）
                Map<String, Object> rechargeProps = new HashMap<>();
                rechargeProps.put("order_no", session.getId());
                rechargeProps.put("package_code", packageType);
                rechargeProps.put("quota_amount", quotaAmount);
                rechargeProps.put("price_cents", priceCents);
                rechargeProps.put("currency", currency);
                analyticsService.capture(clerkUserId, AnalyticsEvents.RECHARGE_SUCCESS, rechargeProps);

                robotNotifyAsyncService.notifyPaymentSucceeded(
                        stripeEventId,
                        session.getId(),
                        clerkUserId,
                        featureCode,
                        packageType != null ? packageType : "unknown",
                        quotaAmount,
                        priceCents,
                        currency,
                        customerEmail,
                        paymentIntentId
                );
            }
        } else {
            log.warn("支付回调跳过充值: clerk_user_id 为空或 quota_amount 无效");
        }
    }

    /**
     * Checkout 会话过期（用户未完成支付即关闭/超时），对应需求「退出付款」
     */
    private void handleCheckoutSessionExpired(Session session, String stripeEventId) {
        Map<String, String> metadata = session.getMetadata();
        String clerkUserId = metadata != null ? metadata.get("clerk_user_id") : null;
        String packageType = metadata != null ? metadata.get("package_type") : null;
        String creditsStr = metadata != null ? metadata.get("credits") : null;
        String featureCode = metadata != null ? metadata.get("feature_code") : null;
        if (featureCode == null || featureCode.isEmpty()) {
            featureCode = FeatureCode.TASK_CREATE.getCode();
        }
        long quotaAmount = 0L;
        try {
            quotaAmount = creditsStr != null && !creditsStr.isEmpty() ? Long.parseLong(creditsStr) : 0L;
        } catch (NumberFormatException ignored) {
        }
        if (quotaAmount <= 0 && packageType != null) {
            Long fromPackage = quotaRechargeService.getQuotaAmountFromPackage(featureCode, packageType);
            if (fromPackage != null) {
                quotaAmount = fromPackage;
            }
        }
        int priceCents = session.getAmountTotal() != null ? session.getAmountTotal().intValue() : 0;
        String currency = session.getCurrency() != null ? session.getCurrency() : "usd";

        robotNotifyAsyncService.notifyCheckoutExpired(
                stripeEventId,
                session.getId(),
                clerkUserId != null ? clerkUserId : "",
                featureCode,
                packageType != null ? packageType : "unknown",
                quotaAmount,
                priceCents,
                currency
        );
    }

    /**
     * 处理 charge.failed 事件（Stripe 实际发送的支付失败事件）
     * Charge 有 failure_code、failure_message，但 metadata 为空，需通过 payment_intent 拉取 PaymentIntent 获取用户信息
     */
    private void handleChargeFailed(Charge charge, String stripeEventType) {
        String paymentIntentId = getPaymentIntentId(charge);
        if (paymentIntentId == null || paymentIntentId.isEmpty()) {
            log.warn("charge.failed 无 payment_intent，跳过");
            return;
        }

        // 从 Charge 直接获取失败原因（failure_code + failure_message）
        String failureCode = charge.getFailureCode();
        String failureMessage = charge.getFailureMessage();
        String failureReason = buildFailureReason(failureCode, failureMessage);

        // Charge 的 metadata 为空，需拉取 PaymentIntent 获取 clerk_user_id、package_type 等
        PaymentIntent paymentIntent = null;
        try {
            paymentIntent = PaymentIntent.retrieve(paymentIntentId);
        } catch (StripeException e) {
            log.warn("拉取 PaymentIntent 失败: payment_intent_id={}, error={}", paymentIntentId, e.getMessage());
        }

        String clerkUserId = null;
        String packageType = null;
        String creditsStr = null;
        String featureCode = FeatureCode.TASK_CREATE.getCode();
        long quotaAmount = 0L;

        if (paymentIntent != null && paymentIntent.getMetadata() != null) {
            Map<String, String> metadata = paymentIntent.getMetadata();
            clerkUserId = metadata.get("clerk_user_id");
            packageType = metadata.get("package_type");
            creditsStr = metadata.get("credits");
            String fc = metadata.get("feature_code");
            if (fc != null && !fc.isEmpty()) {
                featureCode = fc;
            }
            try {
                quotaAmount = creditsStr != null && !creditsStr.isEmpty() ? Long.parseLong(creditsStr) : 0L;
            } catch (NumberFormatException ignored) {
            }
            if (quotaAmount <= 0 && packageType != null) {
                Long fromPackage = quotaRechargeService.getQuotaAmountFromPackage(featureCode, packageType);
                if (fromPackage != null) {
                    quotaAmount = fromPackage;
                }
            }
        }

        int priceCents = charge.getAmount() != null ? charge.getAmount().intValue() : 0;
        String currency = charge.getCurrency() != null ? charge.getCurrency() : "usd";

        log.info("支付失败(charge.failed)！Charge ID: {}, PaymentIntent: {}, 用户: {}, 套餐: {}, 原因: {}",
                charge.getId(), paymentIntentId, clerkUserId, packageType, failureReason);

        boolean recorded = quotaRechargeService.processPaymentFailed(
                clerkUserId != null ? clerkUserId : "",
                featureCode,
                packageType != null ? packageType : "unknown",
                quotaAmount,
                priceCents,
                currency,
                paymentIntentId,
                failureReason
        );
        if (recorded) {
            robotNotifyAsyncService.notifyPaymentFailed(
                    "payment_failed_" + paymentIntentId,
                    clerkUserId != null ? clerkUserId : "",
                    featureCode,
                    packageType != null ? packageType : "unknown",
                    quotaAmount,
                    priceCents,
                    currency,
                    paymentIntentId,
                    failureReason,
                    stripeEventType
            );
        }
    }

    private static String getPaymentIntentId(Charge charge) {
        Object ref = charge.getPaymentIntent();
        if (ref instanceof String) return (String) ref;
        if (ref instanceof PaymentIntent) return ((PaymentIntent) ref).getId();
        return ref != null ? ref.toString() : null;
    }

    private static String buildFailureReason(String failureCode, String failureMessage) {
        if (failureCode != null || failureMessage != null) {
            return (failureCode != null ? failureCode : "") + (failureMessage != null && !failureMessage.isEmpty() ? ": " + failureMessage : "");
        }
        return "Payment failed";
    }

    private void handlePaymentIntentFailed(PaymentIntent paymentIntent, String stripeEventType) {
        String failureReason = "Payment failed";
        try {
            Object lastError = paymentIntent.getLastPaymentError();
            if (lastError != null) {
                if (lastError instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> m = (java.util.Map<String, Object>) lastError;
                    String code = m.get("code") != null ? m.get("code").toString() : null;
                    String message = m.get("message") != null ? m.get("message").toString() : null;
                    failureReason = (code != null ? code : "") + (message != null && !message.isEmpty() ? ": " + message : "");
                } else {
                    failureReason = lastError.toString();
                }
                if (failureReason.isEmpty()) failureReason = "Payment failed";
            }
        } catch (Exception e) {
            log.warn("解析 last_payment_error 失败: {}", e.getMessage());
        }

        Map<String, String> metadata = paymentIntent.getMetadata();
        String clerkUserId = metadata != null ? metadata.get("clerk_user_id") : null;
        String packageType = metadata != null ? metadata.get("package_type") : null;
        String creditsStr = metadata != null ? metadata.get("credits") : null;
        String featureCode = metadata != null ? metadata.get("feature_code") : null;
        if (featureCode == null || featureCode.isEmpty()) {
            featureCode = FeatureCode.TASK_CREATE.getCode();
        }

        long quotaAmount = 0L;
        try {
            quotaAmount = creditsStr != null && !creditsStr.isEmpty() ? Long.parseLong(creditsStr) : 0L;
        } catch (NumberFormatException ignored) {
        }
        if (quotaAmount <= 0 && packageType != null) {
            Long fromPackage = quotaRechargeService.getQuotaAmountFromPackage(featureCode, packageType);
            if (fromPackage != null) {
                quotaAmount = fromPackage;
            }
        }

        int priceCents = paymentIntent.getAmount() != null ? paymentIntent.getAmount().intValue() : 0;
        String currency = paymentIntent.getCurrency() != null ? paymentIntent.getCurrency() : "usd";
        String paymentIntentId = paymentIntent.getId();

        log.info("支付失败！PaymentIntent ID: {}, 用户: {}, 套餐: {}, 原因: {}",
                paymentIntentId, clerkUserId, packageType, failureReason);

        boolean recorded = quotaRechargeService.processPaymentFailed(
                clerkUserId,
                featureCode,
                packageType != null ? packageType : "unknown",
                quotaAmount,
                priceCents,
                currency,
                paymentIntentId,
                failureReason
        );
        if (recorded) {
            robotNotifyAsyncService.notifyPaymentFailed(
                    "payment_failed_" + paymentIntentId,
                    clerkUserId != null ? clerkUserId : "",
                    featureCode,
                    packageType != null ? packageType : "unknown",
                    quotaAmount,
                    priceCents,
                    currency,
                    paymentIntentId,
                    failureReason,
                    stripeEventType
            );
        }
    }
}

