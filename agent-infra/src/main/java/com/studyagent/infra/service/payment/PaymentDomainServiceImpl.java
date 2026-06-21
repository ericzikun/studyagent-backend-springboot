package com.studyagent.infra.service.payment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.checkout.Session;
import com.stripe.param.PriceListParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.studyagent.infra.entity.AiFeaturePackageEntity;
import com.studyagent.infra.mapper.AiFeaturePackageMapper;
import com.studyagent.service.domain.payment.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付领域服务实现
 * 套餐配置从 ai_feature_packages 表读取，维护时直接修改数据库即可
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentDomainServiceImpl implements PaymentDomainService {

    private final AiFeaturePackageMapper aiFeaturePackageMapper;

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    @Value("${stripe.publishable-key:}")
    private String stripePublishableKey;

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

    @Override
    public CheckoutSessionResult createCheckoutSession(CreateCheckoutSessionCommand command) {
        if (stripeSecretKey == null || stripeSecretKey.isEmpty()) {
            throw new PaymentDomainException("STRIPE_NOT_CONFIGURED", "Stripe Secret Key 未配置");
        }

        AiFeaturePackageEntity pkg = findPackageByCode(command.getPackageType());
        if (pkg == null) {
            throw new PaymentDomainException("INVALID_PACKAGE_TYPE", "Invalid package type", command.getPackageType());
        }

        String configuredPriceId = pkg.getStripePriceId();
        if (configuredPriceId == null || configuredPriceId.isEmpty()) {
            throw new PaymentDomainException("PRICE_CONFIG_ERROR", "Package has no Stripe price configured",
                    pkg.getPackageName(), command.getPackageType());
        }

        if (!configuredPriceId.startsWith("price_") && !configuredPriceId.startsWith("prod_")) {
            throw new PaymentDomainException("PRICE_CONFIG_ERROR", "Price ID config error",
                    pkg.getPackageName(), command.getPackageType());
        }

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
                    throw new PaymentDomainException("PRICE_NOT_FOUND", "No valid price found", configuredPriceId);
                }
            } catch (StripeException e) {
                throw new PaymentDomainException("INTERNAL_ERROR", "Find price failed: " + e.getMessage(), e);
            }
        }

        String finalSuccessUrl = command.getSuccessUrl() != null && !command.getSuccessUrl().isEmpty()
                ? command.getSuccessUrl() : successUrl;
        String finalCancelUrl = command.getCancelUrl() != null && !command.getCancelUrl().isEmpty()
                ? command.getCancelUrl() : cancelUrl;

        if (!finalSuccessUrl.contains("{CHECKOUT_SESSION_ID}")) {
            finalSuccessUrl = finalSuccessUrl + (finalSuccessUrl.contains("?") ? "&" : "?") + "session_id={CHECKOUT_SESSION_ID}";
        }

        SessionCreateParams.PaymentIntentData paymentIntentData = SessionCreateParams.PaymentIntentData.builder()
                .putMetadata("package_type", command.getPackageType())
                .putMetadata("feature_code", pkg.getFeatureCode())
                .putMetadata("clerk_user_id", command.getClerkUserId() != null ? command.getClerkUserId() : "")
                .putMetadata("credits", String.valueOf(pkg.getQuotaAmount() != null ? pkg.getQuotaAmount() : 0))
                .build();

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
                .setCustomerEmail(command.getCustomerEmail())
                .putMetadata("package_type", command.getPackageType())
                .putMetadata("feature_code", pkg.getFeatureCode())
                .putMetadata("clerk_user_id", command.getClerkUserId() != null ? command.getClerkUserId() : "")
                .putMetadata("credits", String.valueOf(pkg.getQuotaAmount() != null ? pkg.getQuotaAmount() : 0))
                .setPaymentIntentData(paymentIntentData)
                .build();

        Session session;
        try {
            session = Session.create(params);
        } catch (StripeException e) {
            throw new PaymentDomainException("STRIPE_ERROR", "Create session failed: " + e.getMessage(), e);
        }

        log.info("创建支付会话成功: session_id={}, email={}, package={}, clerk_user_id={}",
                session.getId(), command.getCustomerEmail(), command.getPackageType(), command.getClerkUserId());

        return CheckoutSessionResult.builder()
                .checkoutKind("session")
                .sessionId(session.getId())
                .referenceId(session.getId())
                .checkoutUrl(session.getUrl())
                .expiresAt(session.getExpiresAt())
                .build();
    }

    @Override
    public SessionStatusResult getSessionStatus(String sessionId) {
        Session session;
        try {
            session = Session.retrieve(sessionId);
        } catch (StripeException e) {
            throw new PaymentDomainException("STRIPE_ERROR", "Query session failed: " + e.getMessage(), e);
        }
        return SessionStatusResult.builder()
                .sessionId(session.getId())
                .status(session.getStatus())
                .paymentStatus(session.getPaymentStatus())
                .amountTotal(session.getAmountTotal())
                .currency(session.getCurrency())
                .customerEmail(session.getCustomerDetails() != null ? session.getCustomerDetails().getEmail() : null)
                .createdAt(session.getCreated())
                .clerkUserId(session.getMetadata() != null ? session.getMetadata().get("clerk_user_id") : null)
                .build();
    }

    @Override
    public PaymentConfigResult getPaymentConfig() {
        List<AiFeaturePackageEntity> entities = aiFeaturePackageMapper.selectList(
                new LambdaQueryWrapper<AiFeaturePackageEntity>()
                        .eq(AiFeaturePackageEntity::getIsActive, true)
                        .orderByAsc(AiFeaturePackageEntity::getDisplayOrder));

        List<Map<String, Object>> packages = new ArrayList<>();
        for (AiFeaturePackageEntity e : entities) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", e.getPackageCode());
            m.put("name", e.getPackageName());
            m.put("credits", e.getQuotaAmount() != null ? e.getQuotaAmount().intValue() : 0);
            m.put("unit", resolveQuotaUnit(e.getFeatureCode()));
            m.put("priceId", e.getStripePriceId() != null ? e.getStripePriceId() : "");
            m.put("featureCode", e.getFeatureCode());
            m.put("priceCents", e.getPriceCents());
            m.put("currency", e.getCurrency() != null ? e.getCurrency() : "usd");
            m.put("label", e.getLabel() != null ? e.getLabel() : "normal");
            packages.add(m);
        }

        return PaymentConfigResult.builder()
                .stripePublishableKey(stripePublishableKey)
                .packages(packages)
                .build();
    }

    /**
     * 根据 package_code 查询套餐（前端传的 packageType 即为 package_code）
     */
    private AiFeaturePackageEntity findPackageByCode(String packageCode) {
        if (packageCode == null || packageCode.isBlank()) {
            return null;
        }
        return aiFeaturePackageMapper.selectOne(
                new LambdaQueryWrapper<AiFeaturePackageEntity>()
                        .eq(AiFeaturePackageEntity::getPackageCode, packageCode)
                        .eq(AiFeaturePackageEntity::getIsActive, true));
    }

    private String resolveQuotaUnit(String featureCode) {
        if ("ai_detection".equals(featureCode) || "humanizer".equals(featureCode)) {
            return "words";
        }
        return "time";
    }
}
