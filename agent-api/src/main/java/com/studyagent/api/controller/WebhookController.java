package com.studyagent.api.controller;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Webhook控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/webhook")
@RequiredArgsConstructor
public class WebhookController {
    
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
                Session session = (Session) event.getDataObjectDeserializer()
                    .getObject().orElse(null);
                if (session != null) {
                    handleCheckoutSessionCompleted(session);
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
    
    private void handleCheckoutSessionCompleted(Session session) {
        log.info("支付完成！Session ID: {}, 客户邮箱: {}, 支付金额: ${}, 套餐类型: {}, 获得积分: {}, 用户ID: {}",
            session.getId(),
            session.getCustomerDetails() != null ? session.getCustomerDetails().getEmail() : null,
            session.getAmountTotal() != null ? session.getAmountTotal() / 100.0 : 0,
            session.getMetadata() != null ? session.getMetadata().get("package_type") : null,
            session.getMetadata() != null ? session.getMetadata().get("credits") : null,
            session.getMetadata() != null ? session.getMetadata().get("clerk_user_id") : null
        );
        
        // TODO: 保存订单到数据库
        // TODO: 发放积分到用户账户
    }
}

