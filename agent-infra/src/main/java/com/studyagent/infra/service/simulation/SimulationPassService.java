package com.studyagent.infra.service.simulation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import com.studyagent.infra.entity.SimulationPassEntity;
import com.studyagent.infra.entity.SimulationPassProductEntity;
import com.studyagent.infra.mapper.SimulationPassMapper;
import com.studyagent.infra.mapper.SimulationPassProductMapper;
import com.studyagent.infra.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * One-time Simulation Lab pass: Stripe Checkout creation, webhook fulfillment
 * and the "can create another lab" entitlement lookup. Admins always pass so the
 * feature stays usable internally without a purchase.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationPassService {

    public static final String PASS_CODE = "simulation_lab_7d";
    public static final String PURCHASE_TYPE = "simulation_lab_pass";

    private final SimulationPassMapper passMapper;
    private final SimulationPassProductMapper productMapper;
    private final UserMapper userMapper;

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    @Value("${frontend-url:https://verla.io}")
    private String frontendUrl;

    public record Access(
            boolean active,
            boolean admin,
            boolean purchasable,
            String passCode,
            LocalDateTime expiresAt,
            Integer amountCents,
            String currency,
            Integer accessDays) {
    }

    public Access access(String clerkUserId) {
        SimulationPassProductEntity product = activeProduct();
        SimulationPassEntity pass = activePass(clerkUserId, LocalDateTime.now());
        boolean admin = Boolean.TRUE.equals(userMapper.selectIsAdmin(clerkUserId));
        return new Access(
                admin || pass != null,
                admin,
                product != null,
                PASS_CODE,
                pass == null ? null : pass.getExpiresAt(),
                product == null ? null : product.getAmountCents(),
                product == null ? null : product.getCurrency(),
                product == null ? null : product.getAccessDays());
    }

    public String createCheckout(String clerkUserId) {
        SimulationPassProductEntity product = activeProduct();
        if (product == null || product.getStripePriceId() == null
                || product.getStripePriceId().isBlank()) {
            throw new IllegalStateException("simulation lab pass price is not configured");
        }
        if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
            throw new IllegalStateException("stripe.secret-key is not configured");
        }
        String base = frontendBaseUrl();
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(base
                        + "/dashboard/simulation?purchase=success&session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(base + "/dashboard/simulation?purchase=cancel")
                .setClientReferenceId(clerkUserId)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(product.getStripePriceId())
                        .setQuantity(1L)
                        .build())
                .putMetadata("purchase_type", PURCHASE_TYPE)
                .putMetadata("clerk_user_id", clerkUserId)
                .putMetadata("pass_code", PASS_CODE)
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .putMetadata("purchase_type", PURCHASE_TYPE)
                        .putMetadata("clerk_user_id", clerkUserId)
                        .putMetadata("pass_code", PASS_CODE)
                        .build())
                .build();
        try {
            Session session = Session.create(params,
                    RequestOptions.builder().setApiKey(stripeSecretKey).build());
            if (session.getUrl() == null || session.getUrl().isBlank()) {
                throw new IllegalStateException("stripe returned no checkout url");
            }
            log.info("[SimulationPass] checkout created user={} session={}", clerkUserId, session.getId());
            return session.getUrl();
        } catch (StripeException e) {
            throw new IllegalStateException("create simulation pass checkout failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void fulfillCheckout(String clerkUserId, Session session) {
        if (clerkUserId == null || clerkUserId.isBlank() || session == null
                || session.getId() == null || session.getId().isBlank()) {
            return;
        }
        Long existing = passMapper.selectCount(new LambdaQueryWrapper<SimulationPassEntity>()
                .eq(SimulationPassEntity::getStripeCheckoutSessionId, session.getId()));
        if (existing != null && existing > 0) {
            log.info("[SimulationPass] checkout already fulfilled session={}", session.getId());
            return;
        }
        SimulationPassProductEntity product = activeProduct();
        int accessDays = product == null || product.getAccessDays() == null
                ? 7 : product.getAccessDays();
        LocalDateTime now = LocalDateTime.now();
        SimulationPassEntity current = activePass(clerkUserId, now);
        LocalDateTime expiresAt = (current == null ? now : current.getExpiresAt()).plusDays(accessDays);
        SimulationPassEntity pass = new SimulationPassEntity();
        pass.setClerkUserId(clerkUserId);
        pass.setPassCode(PASS_CODE);
        pass.setSource("stripe");
        pass.setStatus("active");
        pass.setStartedAt(now);
        pass.setExpiresAt(expiresAt);
        pass.setStripeCheckoutSessionId(session.getId());
        pass.setStripePaymentIntentId(session.getPaymentIntent());
        pass.setAmountCents(session.getAmountTotal() == null ? null : session.getAmountTotal().intValue());
        pass.setCurrency(session.getCurrency());
        try {
            passMapper.insert(pass);
            log.info("[SimulationPass] granted user={} session={} expiresAt={}",
                    clerkUserId, session.getId(), expiresAt);
        } catch (DuplicateKeyException e) {
            log.info("[SimulationPass] duplicate fulfillment ignored session={}", session.getId());
        }
    }

    private SimulationPassEntity activePass(String clerkUserId, LocalDateTime now) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return null;
        }
        return passMapper.selectOne(new LambdaQueryWrapper<SimulationPassEntity>()
                .eq(SimulationPassEntity::getClerkUserId, clerkUserId)
                .eq(SimulationPassEntity::getStatus, "active")
                .gt(SimulationPassEntity::getExpiresAt, now)
                .orderByDesc(SimulationPassEntity::getExpiresAt)
                .last("LIMIT 1"));
    }

    private SimulationPassProductEntity activeProduct() {
        return productMapper.selectOne(new LambdaQueryWrapper<SimulationPassProductEntity>()
                .eq(SimulationPassProductEntity::getPassCode, PASS_CODE)
                .eq(SimulationPassProductEntity::getIsActive, true)
                .last("LIMIT 1"));
    }

    private String frontendBaseUrl() {
        String base = frontendUrl == null ? "" : frontendUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.isEmpty() ? "https://verla.io" : base;
    }
}
