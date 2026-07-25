package com.studyagent.api.service.robot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.UserProfileEntity;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.service.domain.billing.BillingCheckoutNotifyRequest;
import com.studyagent.service.domain.billing.BillingPaymentFailedNotifyRequest;
import com.studyagent.service.domain.billing.BillingReviewNotifyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * V2 商业化（subscription / addon / upgrade）飞书机器人播报。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RobotNotifyBillingService {

    private static final String ORDER_COMPLETED = "completed";
    private static final Set<String> BILLING_ORDER_TYPES = Set.of(
            "subscription_initial",
            "subscription_upgrade",
            "subscription_upgrade_manual",
            "subscription_renewal",
            "addon"
    );

    private final RobotNotifyService robotNotifyService;
    private final RechargeOrderMapper rechargeOrderMapper;

    @Value("${app.public-site-url:http://localhost:3000}")
    private String publicSiteUrl;

    @Async("robotNotifyExecutor")
    public void notifyCheckoutSucceeded(BillingCheckoutNotifyRequest request) {
        try {
            String purchaseType = normalizePurchaseType(request.getPurchaseType());
            boolean repurchase = isBillingRepurchase(request.getClerkUserId(), request.getSessionId());
            String title = buildBillingTitle(purchaseType, "付款成功", repurchase);
            String scene = resolveSuccessScene(purchaseType);
            String md = buildCheckoutSuccessMarkdown(request, purchaseType, repurchase);
            Map<String, Object> meta = baseBillingMeta("billing_checkout_success", request);
            meta.put("purchase_type", purchaseType);
            robotNotifyService.dispatch(
                    RobotNotifyRouteKind.ASSIGNMENT,
                    request.getStripeEventId(),
                    scene,
                    title,
                    truncate(md, 2000),
                    meta
            );
        } catch (Exception e) {
            log.warn("notifyCheckoutSucceeded failed: {}", e.getMessage(), e);
        }
    }

    @Async("robotNotifyExecutor")
    public void notifyCheckoutExpired(BillingCheckoutNotifyRequest request) {
        try {
            String purchaseType = normalizePurchaseType(request.getPurchaseType());
            boolean repurchase = isBillingRepurchase(request.getClerkUserId(), request.getSessionId());
            String title = buildBillingTitle(purchaseType, "退出付款", repurchase);
            StringBuilder sb = new StringBuilder();
            sb.append("**Stripe 事件**: ").append(nullToDash(request.getStripeEventType())).append("\n\n");
            sb.append("- **购买类型**: ").append(purchaseTypeLabel(purchaseType)).append("\n");
            sb.append("- **套餐/计划**: ").append(formatBillingProductLine(request)).append("\n");
            sb.append("- **UID**: ").append(nullToDash(request.getClerkUserId())).append("\n");
            sb.append("- **金额**: ").append(formatMoney(request.getPriceCents(), request.getCurrency())).append("\n");
            sb.append("- **Session**: ").append(nullToDash(request.getSessionId())).append("\n");
            sb.append("- **时间(北京时间)**: ").append(appNowLabel()).append("\n");
            Map<String, Object> meta = baseBillingMeta("billing_checkout_expired", request);
            meta.put("purchase_type", purchaseType);
            robotNotifyService.dispatch(
                    RobotNotifyRouteKind.ASSIGNMENT,
                    request.getStripeEventId(),
                    "notify.checkout.expired",
                    title,
                    truncate(sb.toString(), 2000),
                    meta
            );
        } catch (Exception e) {
            log.warn("notifyCheckoutExpired failed: {}", e.getMessage(), e);
        }
    }

    @Async("robotNotifyExecutor")
    public void notifyPaymentFailed(BillingPaymentFailedNotifyRequest request) {
        try {
            String purchaseType = normalizePurchaseType(request.getPurchaseType());
            boolean repurchase = isBillingRepurchase(request.getClerkUserId(), null);
            String title = buildBillingTitle(purchaseType, "付款失败", repurchase);
            StringBuilder sb = new StringBuilder();
            sb.append("**Stripe 事件**: ").append(nullToDash(request.getStripeEventType())).append("\n\n");
            sb.append("- **购买类型**: ").append(purchaseTypeLabel(purchaseType)).append("\n");
            sb.append("- **计划/Addon**: ").append(formatFailedProductLine(request)).append("\n");
            sb.append("- **UID**: ").append(nullToDash(request.getClerkUserId())).append("\n");
            sb.append("- **金额**: ").append(formatMoney(request.getPriceCents(), request.getCurrency())).append("\n");
            sb.append("- **失败原因**: ").append(nullToDash(request.getFailureReason())).append("\n");
            sb.append("- **PaymentIntent**: ").append(nullToDash(request.getPaymentIntentId())).append("\n");
            sb.append("- **Invoice**: ").append(nullToDash(request.getInvoiceId())).append("\n");
            sb.append("- **时间(北京时间)**: ").append(appNowLabel()).append("\n");
            Map<String, Object> meta = new HashMap<>();
            meta.put("kind", "billing_payment_failed");
            meta.put("notify_event_id", request.getNotifyEventId());
            meta.put("clerk_user_id", request.getClerkUserId());
            meta.put("purchase_type", purchaseType);
            robotNotifyService.dispatch(
                    RobotNotifyRouteKind.ASSIGNMENT,
                    request.getNotifyEventId(),
                    "notify.payment.failed",
                    title,
                    truncate(sb.toString(), 2000),
                    meta
            );
        } catch (Exception e) {
            log.warn("notifyPaymentFailed failed: {}", e.getMessage(), e);
        }
    }

    @Async("robotNotifyExecutor")
    public void notifyBillingReviewRequired(BillingReviewNotifyRequest request) {
        try {
            String title = "商业化事件需人工处理";
            StringBuilder sb = new StringBuilder();
            sb.append("**Stripe 事件**: ")
                    .append(nullToDash(request.getStripeEventType()))
                    .append("\n\n");
            sb.append("- **处理状态**: ").append(nullToDash(request.getStatus())).append("\n");
            sb.append("- **事件 ID**: ").append(nullToDash(request.getStripeEventId())).append("\n");
            sb.append("- **对象 ID**: ").append(nullToDash(request.getObjectId())).append("\n");
            sb.append("- **尝试次数**: ").append(request.getAttemptCount()).append("\n");
            sb.append("- **原因**: ").append(nullToDash(request.getReason())).append("\n");
            sb.append("- **时间(北京时间)**: ").append(appNowLabel()).append("\n");
            Map<String, Object> meta = new HashMap<>();
            meta.put("kind", "billing_review_required");
            meta.put("stripe_event_id", request.getStripeEventId());
            meta.put("stripe_event_type", request.getStripeEventType());
            meta.put("status", request.getStatus());
            robotNotifyService.dispatch(
                    RobotNotifyRouteKind.ASSIGNMENT,
                    "billing_review_" + request.getStripeEventId() + "_" + request.getStatus(),
                    "notify.billing.review-required",
                    title,
                    truncate(sb.toString(), 2000),
                    meta
            );
        } catch (Exception e) {
            log.warn("notifyBillingReviewRequired failed: {}", e.getMessage(), e);
        }
    }

    private String buildCheckoutSuccessMarkdown(
            BillingCheckoutNotifyRequest request,
            String purchaseType,
            boolean repurchase
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Stripe 事件**: ").append(nullToDash(request.getStripeEventType())).append("\n\n");
        sb.append("- **购买类型 · 状态 · 首复购**: ")
                .append(purchaseTypeLabel(purchaseType)).append(" · 付款成功 · ")
                .append(repurchase ? "复购" : "首购").append("\n");
        sb.append("- **套餐/计划**: ").append(formatBillingProductLine(request)).append("\n");
        sb.append("- **UID**: ").append(nullToDash(request.getClerkUserId())).append("\n");
        sb.append("- **邮箱(Stripe/会话)**: ").append(nullToDash(request.getCustomerEmail())).append("\n");
        sb.append("- **金额**: ").append(formatMoney(request.getPriceCents(), request.getCurrency())).append("\n");
        if (repurchase) {
            sb.append("- **复购间隔(天)**: ").append(repurchaseGapDays(request.getClerkUserId(), request.getSessionId())).append("\n");
            sb.append("- **累计付费(商业化)**: ").append(sumCompletedBillingUsd(request.getClerkUserId())).append("\n");
        }
        sb.append("- **账户页**: ").append(accountPageLink()).append("\n");
        sb.append("- **时间(北京时间)**: ").append(appNowLabel()).append("\n");
        sb.append("- **Stripe**: session=").append(nullToDash(request.getSessionId()))
                .append(", pi=").append(nullToDash(request.getPaymentIntentId()))
                .append(", evt=").append(nullToDash(request.getStripeEventId()));
        return sb.toString();
    }

    private String resolveSuccessScene(String purchaseType) {
        return switch (purchaseType) {
            case "addon" -> "notify.addon.success";
            case "subscription_upgrade_manual" -> "notify.subscription.upgrade.success";
            case "subscription" -> "notify.subscription.initial.success";
            default -> "notify.payment.success";
        };
    }

    private boolean isBillingRepurchase(String clerkUserId, String excludeSessionId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return false;
        }
        var query = new LambdaQueryWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                .eq(RechargeOrderEntity::getStatus, ORDER_COMPLETED);
        if (excludeSessionId != null && !excludeSessionId.isBlank()) {
            query.ne(RechargeOrderEntity::getStripeSessionId, excludeSessionId);
        }
        Long count = rechargeOrderMapper.selectCount(query);
        return count != null && count > 0;
    }

    private String repurchaseGapDays(String clerkUserId, String currentSessionId) {
        var query = new LambdaQueryWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                .eq(RechargeOrderEntity::getStatus, ORDER_COMPLETED)
                .isNotNull(RechargeOrderEntity::getPaidAt)
                .orderByDesc(RechargeOrderEntity::getPaidAt)
                .last("LIMIT 1");
        if (currentSessionId != null && !currentSessionId.isBlank()) {
            query.ne(RechargeOrderEntity::getStripeSessionId, currentSessionId);
        }
        RechargeOrderEntity prev = rechargeOrderMapper.selectOne(query);
        if (prev == null || prev.getPaidAt() == null) {
            return "—";
        }
        long days = ChronoUnit.DAYS.between(prev.getPaidAt().toLocalDate(), LocalDate.now(DateTimeFormats.APP_ZONE));
        return String.valueOf(days);
    }

    private String sumCompletedBillingUsd(String clerkUserId) {
        var orders = rechargeOrderMapper.selectList(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                        .eq(RechargeOrderEntity::getStatus, ORDER_COMPLETED));
        long cents = orders.stream()
                .filter(o -> isBillingOrder(o) || "subscription".equals(o.getFeatureCode()))
                .mapToLong(o -> o.getPriceCents() != null ? o.getPriceCents() : 0L)
                .sum();
        return formatUsd(cents);
    }

    private static boolean isBillingOrder(RechargeOrderEntity order) {
        if (order.getOrderType() == null) {
            return false;
        }
        return BILLING_ORDER_TYPES.contains(order.getOrderType())
                || order.getOrderType().startsWith("subscription");
    }

    private static Map<String, Object> baseBillingMeta(String kind, BillingCheckoutNotifyRequest request) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("kind", kind);
        meta.put("stripe_event_id", request.getStripeEventId());
        meta.put("clerk_user_id", request.getClerkUserId());
        meta.put("stripe_session_id", request.getSessionId());
        return meta;
    }

    private String buildBillingTitle(String purchaseType, String state, boolean repurchase) {
        String product = purchaseTypeLabel(purchaseType);
        String rp = repurchase ? "复购" : "首购";
        return String.format(Locale.US, "[付费播报] %s · %s · %s", product, state, rp);
    }

    private static String normalizePurchaseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "subscription";
        }
        return raw.trim();
    }

    private static String purchaseTypeLabel(String purchaseType) {
        return switch (purchaseType) {
            case "addon" -> "Addon 加购";
            case "subscription_upgrade_manual" -> "订阅升级";
            case "subscription" -> "会员订阅";
            default -> purchaseType;
        };
    }

    private static String formatBillingProductLine(BillingCheckoutNotifyRequest request) {
        if ("addon".equals(normalizePurchaseType(request.getPurchaseType()))) {
            String addon = nullToDash(request.getAddonCode());
            String feature = request.getFeatureCode() != null ? request.getFeatureCode() : "—";
            long quota = request.getQuotaAmount();
            return addon + " · " + feature + " · quota=" + quota;
        }
        if ("subscription_upgrade_manual".equals(normalizePurchaseType(request.getPurchaseType()))) {
            return "升级至 " + nullToDash(request.getTargetPlanCode());
        }
        return nullToDash(request.getPlanCode());
    }

    private static String formatFailedProductLine(BillingPaymentFailedNotifyRequest request) {
        if ("addon".equals(normalizePurchaseType(request.getPurchaseType()))) {
            return nullToDash(request.getAddonCode());
        }
        return nullToDash(request.getPlanCode());
    }

    private String accountPageLink() {
        return publicSiteUrl.replaceAll("/+$", "") + "/dashboard";
    }

    private static String formatMoney(int priceCents, String currency) {
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : "USD";
        BigDecimal amt = BigDecimal.valueOf(priceCents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return amt + " " + cur;
    }

    private static String formatUsd(long cents) {
        BigDecimal amt = BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return "$" + amt;
    }

    private static String appNowLabel() {
        return DateTimeFormats.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " (UTC+8)";
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }
}
