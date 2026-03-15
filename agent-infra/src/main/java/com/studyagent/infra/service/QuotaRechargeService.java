package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 额度充值服务
 * 处理 Stripe 支付成功后的用户充值逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaRechargeService {

    private final RechargeOrderMapper rechargeOrderMapper;
    private final UserAiQuotaMapper userAiQuotaMapper;
    private final QuotaLedgerMapper quotaLedgerMapper;
    private final AiFeaturePackageMapper aiFeaturePackageMapper;
    private final AiFeatureDefsMapper aiFeatureDefsMapper;

    private static final String LEDGER_TYPE_RECHARGE = "recharge";
    private static final String PERIOD_MONTHLY = "monthly";
    private static final String PERIOD_WEEKLY = "weekly";
    private static final String PERIOD_DAILY = "daily";
    private static final String SOURCE_TYPE_ORDER = "order";
    private static final String ORDER_STATUS_COMPLETED = "completed";
    private static final String ORDER_STATUS_FAILED = "failed";

    /**
     * 处理支付成功，执行充值到账逻辑
     * 幂等：同一 stripe_session_id 重复调用不会重复发放
     *
     * @param clerkUserId     Clerk 用户 ID（来自 metadata）
     * @param featureCode     功能编码 task_create/ai_detection/humanizer
     * @param packageCode     套餐编码 assignment_1/assignment_5/starter/pro/academic 等
     * @param quotaAmount     到账额度（来自 metadata.credits 或套餐表）
     * @param priceCents      实付金额（分）
     * @param currency        货币
     * @param stripeSessionId Stripe Checkout Session ID
     * @param paymentIntentId Stripe Payment Intent ID（可选）
     * @return 是否成功处理（false 表示已处理过，幂等跳过）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean processRecharge(
            String clerkUserId,
            String featureCode,
            String packageCode,
            long quotaAmount,
            int priceCents,
            String currency,
            String stripeSessionId,
            String paymentIntentId) {

        if (clerkUserId == null || clerkUserId.isEmpty()) {
            log.warn("充值失败: clerk_user_id 为空, session_id={}", stripeSessionId);
            return false;
        }
        if (featureCode == null || featureCode.isEmpty()) {
            featureCode = FeatureCode.TASK_CREATE.getCode();
        }

        // 幂等校验：已存在则跳过
        RechargeOrderEntity existing = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getStripeSessionId, stripeSessionId)
                        .last("LIMIT 1"));
        if (existing != null) {
            log.info("充值幂等跳过: order_no={} 已存在, session_id={}", existing.getOrderNo(), stripeSessionId);
            return false;
        }

        String orderNo = generateOrderNo();

        // 1. 插入 recharge_orders
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setOrderNo(orderNo);
        order.setClerkUserId(clerkUserId);
        order.setFeatureCode(featureCode);
        order.setPackageCode(packageCode);
        order.setQuotaAmount(quotaAmount);
        order.setPriceCents(priceCents);
        order.setCurrency(currency != null ? currency : "usd");
        order.setStripeSessionId(stripeSessionId);
        order.setStripePaymentIntentId(paymentIntentId);
        order.setStatus(ORDER_STATUS_COMPLETED);
        order.setPaidAt(LocalDateTime.now());
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        rechargeOrderMapper.insert(order);

        // 2. 更新 user_ai_quotas：存在则增加 paid_balance，不存在则新建
        UserAiQuotaEntity quota = userAiQuotaMapper.selectOne(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, clerkUserId)
                        .eq(UserAiQuotaEntity::getFeatureCode, featureCode)
                        .last("LIMIT 1"));

        long newPaidBalance;
        if (quota == null) {
            // 新用户：从 ai_feature_defs 赋予应有免费额度，确保先扣免费再扣付费
            long freeQuotaAmount = 0L;
            LocalDateTime periodStart = LocalDateTime.now();
            LocalDateTime periodEnd = periodStart.plusMonths(1);
            AiFeatureDefsEntity featureDef = aiFeatureDefsMapper.selectOne(
                    new LambdaQueryWrapper<AiFeatureDefsEntity>()
                            .eq(AiFeatureDefsEntity::getFeatureCode, featureCode)
                            .eq(AiFeatureDefsEntity::getIsActive, true)
                            .last("LIMIT 1"));
            if (featureDef != null) {
                freeQuotaAmount = featureDef.getFreeQuotaAmount() != null ? featureDef.getFreeQuotaAmount() : 0L;
                periodEnd = computePeriodEnd(periodStart, featureDef.getFreeQuotaPeriod());
            }

            quota = new UserAiQuotaEntity();
            quota.setClerkUserId(clerkUserId);
            quota.setFeatureCode(featureCode);
            quota.setFreeBalance(freeQuotaAmount);
            quota.setFreePeriodStart(periodStart);
            quota.setFreePeriodEnd(periodEnd);
            quota.setPaidBalance(quotaAmount);
            quota.setVersion(0);
            quota.setCreatedAt(LocalDateTime.now());
            quota.setUpdatedAt(LocalDateTime.now());
            userAiQuotaMapper.insert(quota);
            newPaidBalance = quotaAmount;
        } else {
            newPaidBalance = (quota.getPaidBalance() != null ? quota.getPaidBalance() : 0L) + quotaAmount;
            userAiQuotaMapper.update(null, new LambdaUpdateWrapper<UserAiQuotaEntity>()
                    .eq(UserAiQuotaEntity::getId, quota.getId())
                    .set(UserAiQuotaEntity::getPaidBalance, newPaidBalance)
                    .set(UserAiQuotaEntity::getUpdatedAt, LocalDateTime.now())
                    .setSql("version = version + 1"));
        }

        // 3. 写入 quota_ledger
        String ledgerNo = generateLedgerNo();
        QuotaLedgerEntity ledger = new QuotaLedgerEntity();
        ledger.setLedgerNo(ledgerNo);
        ledger.setClerkUserId(clerkUserId);
        ledger.setFeatureCode(featureCode);
        ledger.setLedgerType(LEDGER_TYPE_RECHARGE);
        ledger.setAmount(quotaAmount);
        ledger.setSourceType(SOURCE_TYPE_ORDER);
        ledger.setSourceId(orderNo);
        ledger.setFreeBalanceAfter(quota.getFreeBalance() != null ? quota.getFreeBalance() : 0L);
        ledger.setPaidBalanceAfter(newPaidBalance);
        Map<String, Object> bizContext = new HashMap<>();
        bizContext.put("order_no", orderNo);
        bizContext.put("package_code", packageCode);
        bizContext.put("quota_amount", quotaAmount);
        bizContext.put("price_cents", priceCents);
        ledger.setBizContext(new com.google.gson.Gson().toJson(bizContext));
        ledger.setCreatedAt(LocalDateTime.now());
        quotaLedgerMapper.insert(ledger);

        log.info("充值成功: clerk_user_id={}, order_no={}, package={}, quota_amount={}, paid_balance_after={}",
                clerkUserId, orderNo, packageCode, quotaAmount, newPaidBalance);
        return true;
    }

    /**
     * 记录支付失败（Stripe payment_intent.payment_failed 回调）
     * 幂等：同一 stripe_payment_intent_id 重复调用不会重复插入
     *
     * @param clerkUserId       Clerk 用户 ID（来自 PaymentIntent metadata）
     * @param featureCode       功能编码
     * @param packageCode       套餐编码
     * @param quotaAmount       预期额度
     * @param priceCents        金额（分）
     * @param currency          货币
     * @param paymentIntentId   Stripe Payment Intent ID
     * @param failureReason     失败原因（如 card_declined、insufficient_funds 等）
     * @return 是否成功记录（false 表示已存在，幂等跳过）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean processPaymentFailed(
            String clerkUserId,
            String featureCode,
            String packageCode,
            long quotaAmount,
            int priceCents,
            String currency,
            String paymentIntentId,
            String failureReason) {

        if (paymentIntentId == null || paymentIntentId.isEmpty()) {
            log.warn("支付失败记录跳过: payment_intent_id 为空");
            return false;
        }

        // 幂等校验：已存在则跳过
        RechargeOrderEntity existing = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getStripePaymentIntentId, paymentIntentId)
                        .last("LIMIT 1"));
        if (existing != null) {
            log.info("支付失败记录幂等跳过: order_no={} 已存在, payment_intent_id={}", existing.getOrderNo(), paymentIntentId);
            return false;
        }

        if (featureCode == null || featureCode.isEmpty()) {
            featureCode = FeatureCode.TASK_CREATE.getCode();
        }
        if (clerkUserId == null) {
            clerkUserId = "";
        }
        if (packageCode == null) {
            packageCode = "unknown";
        }

        String orderNo = generateOrderNo();

        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setOrderNo(orderNo);
        order.setClerkUserId(clerkUserId);
        order.setFeatureCode(featureCode);
        order.setPackageCode(packageCode);
        order.setQuotaAmount(quotaAmount);
        order.setPriceCents(priceCents);
        order.setCurrency(currency != null ? currency : "usd");
        order.setStripeSessionId(null);
        order.setStripePaymentIntentId(paymentIntentId);
        order.setStatus(ORDER_STATUS_FAILED);
        order.setFailureReason(failureReason != null ? truncate(failureReason, 512) : null);
        order.setPaidAt(null);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        rechargeOrderMapper.insert(order);

        log.info("支付失败已记录: order_no={}, payment_intent_id={}, clerk_user_id={}, package={}, reason={}",
                orderNo, paymentIntentId, clerkUserId, packageCode, failureReason);
        return true;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    /**
     * 从套餐表获取额度（若 metadata 未传 credits 则使用）
     */
    public Long getQuotaAmountFromPackage(String featureCode, String packageCode) {
        AiFeaturePackageEntity pkg = aiFeaturePackageMapper.selectOne(
                new LambdaQueryWrapper<AiFeaturePackageEntity>()
                        .eq(AiFeaturePackageEntity::getFeatureCode, featureCode)
                        .eq(AiFeaturePackageEntity::getPackageCode, packageCode)
                        .eq(AiFeaturePackageEntity::getIsActive, true)
                        .last("LIMIT 1"));
        return pkg != null ? pkg.getQuotaAmount() : null;
    }

    private String generateOrderNo() {
        return "RO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + 
                UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private LocalDateTime computePeriodEnd(LocalDateTime start, String period) {
        if (period == null) {
            period = PERIOD_MONTHLY;
        }
        return switch (period) {
            case PERIOD_DAILY -> start.plusDays(1);
            case PERIOD_WEEKLY -> start.plusWeeks(1);
            default -> start.plusMonths(1);
        };
    }

    private String generateLedgerNo() {
        return "QL" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) +
                UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}
