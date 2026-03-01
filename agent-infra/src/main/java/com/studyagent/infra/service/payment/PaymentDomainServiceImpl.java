package com.studyagent.infra.service.payment;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.checkout.Session;
import com.stripe.param.PriceListParams;
import com.stripe.param.checkout.SessionCreateParams;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentDomainServiceImpl implements PaymentDomainService {

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    @Value("${stripe.publishable-key:}")
    private String stripePublishableKey;

    @Value("${stripe.price.assignment_1:}")
    private String priceAssignment1;

    @Value("${stripe.price.assignment_5:}")
    private String priceAssignment5;

    @Value("${stripe.price.assignment_10:}")
    private String priceAssignment10;

    @Value("${stripe.price.assignment_50:}")
    private String priceAssignment50;

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

    @Override
    public CheckoutSessionResult createCheckoutSession(CreateCheckoutSessionCommand command) {
        if (stripeSecretKey == null || stripeSecretKey.isEmpty()) {
            throw new PaymentDomainException("STRIPE_NOT_CONFIGURED", "Stripe Secret Key 未配置");
        }

        String configuredPriceId = getPriceId(command.getPackageType());
        if (configuredPriceId == null || configuredPriceId.isEmpty()) {
            throw new PaymentDomainException("INVALID_PACKAGE_TYPE", "Invalid package type", command.getPackageType());
        }

        if (!configuredPriceId.startsWith("price_") && !configuredPriceId.startsWith("prod_")) {
            throw new PaymentDomainException("PRICE_CONFIG_ERROR", "Price ID config error",
                    getPackageName(command.getPackageType()), command.getPackageType().toUpperCase());
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
                .putMetadata("feature_code", getFeatureCode(command.getPackageType()))
                .putMetadata("clerk_user_id", command.getClerkUserId() != null ? command.getClerkUserId() : "")
                .putMetadata("credits", String.valueOf(getCredits(command.getPackageType())))
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
                .sessionId(session.getId())
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
        List<Map<String, Object>> packages = new ArrayList<>();
        packages.add(buildPackage("assignment_1", "1 Assignment", 1, priceAssignment1));
        packages.add(buildPackage("assignment_5", "5 Assignments", 5, priceAssignment5));
        packages.add(buildPackage("assignment_10", "10 Assignments", 10, priceAssignment10));
        packages.add(buildPackage("assignment_50", "50 Assignments", 50, priceAssignment50));
        packages.add(buildPackage("starter", "Starter Pack", 1, priceStarter));
        packages.add(buildPackage("pro", "Pro Pack", 10, pricePro));
        packages.add(buildPackage("academic", "Academic Pack", 50, priceAcademic));

        return PaymentConfigResult.builder()
                .stripePublishableKey(stripePublishableKey)
                .packages(packages)
                .build();
    }

    private Map<String, Object> buildPackage(String type, String name, int credits, String priceId) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("name", name);
        m.put("credits", credits);
        m.put("priceId", priceId != null ? priceId : "");
        return m;
    }

    private String getPriceId(String packageType) {
        return switch (packageType) {
            case "assignment_1" -> priceAssignment1;
            case "assignment_5" -> priceAssignment5;
            case "assignment_10" -> priceAssignment10;
            case "assignment_50" -> priceAssignment50;
            case "starter" -> priceStarter;
            case "pro" -> pricePro;
            case "academic" -> priceAcademic;
            default -> null;
        };
    }

    private int getCredits(String packageType) {
        return switch (packageType) {
            case "assignment_1" -> 1;
            case "assignment_5" -> 5;
            case "assignment_10" -> 10;
            case "assignment_50" -> 50;
            case "starter" -> 1;
            case "pro" -> 10;
            case "academic" -> 50;
            default -> 0;
        };
    }

    private String getFeatureCode(String packageType) {
        if (packageType != null && (packageType.startsWith("assignment_") || packageType.equals("starter")
                || packageType.equals("pro") || packageType.equals("academic"))) {
            return "task_create";
        }
        return "task_create";
    }

    private String getPackageName(String packageType) {
        return switch (packageType) {
            case "assignment_1" -> "Assignment 1次";
            case "assignment_5" -> "Assignment 5次";
            case "assignment_10" -> "Assignment 10次";
            case "assignment_50" -> "Assignment 50次";
            case "starter" -> "Starter";
            case "pro" -> "Pro";
            case "academic" -> "Academic";
            default -> packageType;
        };
    }
}
