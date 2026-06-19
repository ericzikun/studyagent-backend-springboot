package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.Gson;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.infra.entity.AiFeatureDefsEntity;
import com.studyagent.infra.entity.QuotaLedgerEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.entity.UserAiQuotaEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.AiFeatureDefsMapper;
import com.studyagent.infra.mapper.QuotaLedgerMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.UserAiQuotaMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.quota.PlanQuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlanQuotaServiceImpl implements PlanQuotaService {
    private static final Gson GSON = new Gson();
    private static final List<String> PLAN_FEATURE_CODES = List.of(
            FeatureCode.TASK_CREATE.getCode(),
            FeatureCode.AI_DETECTION.getCode(),
            FeatureCode.HUMANIZER.getCode());
    private static final String LEDGER_TYPE_PLAN_RESET = "plan_reset";
    private static final String LEDGER_TYPE_UPGRADE_GRANT = "upgrade_grant";
    private static final String LEDGER_TYPE_PLAN_CLEAR = "plan_clear";
    private static final String LEDGER_TYPE_PLAN_REFRESH = "plan_refresh";
    private static final String LEDGER_TYPE_PLAN_EXPIRED = "plan_expired";

    private final SubscriptionPlanMapper subscriptionPlanMapper;
    private final AiFeatureDefsMapper aiFeatureDefsMapper;
    private final UserAiQuotaMapper userAiQuotaMapper;
    private final QuotaLedgerMapper quotaLedgerMapper;
    private final UserSubscriptionMapper userSubscriptionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshPlanQuotaIfNeeded(String clerkUserId, String featureCode) {
        refreshPlanQuotaIfNeeded(clerkUserId, featureCode, LocalDateTime.now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshAllPlanQuotasIfNeeded(String clerkUserId) {
        refreshAllPlanQuotasIfNeeded(clerkUserId, LocalDateTime.now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetFromPaidInvoice(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant quotaPeriodStart,
            Instant quotaPeriodEnd,
            String invoiceId) {
        applyPlanGrant(
                clerkUserId,
                subscriptionId,
                planCode,
                quotaPeriodStart,
                quotaPeriodEnd,
                invoiceId,
                LEDGER_TYPE_PLAN_RESET,
                false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addFullPlanForUpgrade(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant quotaPeriodStart,
            Instant quotaPeriodEnd,
            String invoiceId) {
        applyPlanGrant(
                clerkUserId,
                subscriptionId,
                planCode,
                quotaPeriodStart,
                quotaPeriodEnd,
                invoiceId,
                LEDGER_TYPE_UPGRADE_GRANT,
                true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearPlanQuota(String clerkUserId, String subscriptionId, String idempotencyKey) {
        List<UserAiQuotaEntity> quotas = userAiQuotaMapper.selectList(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, clerkUserId)
                        .in(UserAiQuotaEntity::getFeatureCode, List.of(
                                FeatureCode.TASK_CREATE.getCode(),
                                FeatureCode.AI_DETECTION.getCode(),
                                FeatureCode.HUMANIZER.getCode())));
        LocalDateTime now = LocalDateTime.now();
        for (UserAiQuotaEntity quota : quotas) {
            if (quota == null || quota.getId() == null) {
                continue;
            }
            if (hasLedger(quota.getFeatureCode(), LEDGER_TYPE_PLAN_CLEAR, idempotencyKey)) {
                continue;
            }
            long previousPlanBalance = quota.getPlanBalance() != null ? quota.getPlanBalance() : 0L;
            quota.setPlanBalance(0L);
            quota.setPlanPeriodStart(null);
            quota.setPlanPeriodEnd(null);
            quota.setUpdatedAt(now);
            clearPlanWindowOrThrow(quota, now);

            QuotaLedgerEntity ledger = new QuotaLedgerEntity();
            ledger.setLedgerNo(generateLedgerNo());
            ledger.setClerkUserId(clerkUserId);
            ledger.setFeatureCode(quota.getFeatureCode());
            ledger.setLedgerType(LEDGER_TYPE_PLAN_CLEAR);
            ledger.setAmount(-previousPlanBalance);
            ledger.setSourceType("subscription");
            ledger.setSourceId(subscriptionId);
            ledger.setIdempotencyKey(idempotencyKey);
            ledger.setSubscriptionId(subscriptionId);
            ledger.setFreeBalanceAfter(quota.getFreeBalance());
            ledger.setPlanBalanceAfter(0L);
            ledger.setPaidBalanceAfter(quota.getPaidBalance());
            ledger.setCreatedAt(now);
            quotaLedgerMapper.insert(ledger);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    void refreshPlanQuotaIfNeeded(String clerkUserId, String featureCode, LocalDateTime now) {
        if (clerkUserId == null || clerkUserId.isBlank() || featureCode == null || featureCode.isBlank()) {
            return;
        }

        UserSubscriptionEntity subscription = userSubscriptionMapper.selectByUserForUpdate(clerkUserId);
        if (subscription == null || subscription.getPlanCode() == null || subscription.getPlanCode().isBlank()) {
            return;
        }

        UserAiQuotaEntity quota = userAiQuotaMapper.selectOne(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, clerkUserId)
                        .eq(UserAiQuotaEntity::getFeatureCode, featureCode)
                        .last("LIMIT 1"));
        if (quota == null) {
            return;
        }

        LocalDateTime currentPlanEnd = quota.getPlanPeriodEnd();
        boolean missingPlanWindow = quota.getPlanPeriodStart() == null || currentPlanEnd == null;
        boolean planWindowExpired = currentPlanEnd != null && now.isAfter(currentPlanEnd);
        if (!missingPlanWindow && !planWindowExpired) {
            return;
        }

        SubscriptionPlanEntity plan = requirePlan(subscription.getPlanCode());
        String billingInterval = normalizeBillingInterval(plan.getBillingInterval());
        if ("year".equals(billingInterval)) {
            refreshAnnualPlanQuota(clerkUserId, subscription, quota, featureCode, plan, now);
            return;
        }

        if ("month".equals(billingInterval) && planWindowExpired) {
            expireMonthlyPlanQuota(clerkUserId, subscription, quota, featureCode, now);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    void refreshAllPlanQuotasIfNeeded(String clerkUserId, LocalDateTime now) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return;
        }

        UserSubscriptionEntity subscription = userSubscriptionMapper.selectByUserForUpdate(clerkUserId);
        if (subscription == null || subscription.getPlanCode() == null || subscription.getPlanCode().isBlank()) {
            return;
        }

        SubscriptionPlanEntity plan = requirePlan(subscription.getPlanCode());
        String billingInterval = normalizeBillingInterval(plan.getBillingInterval());

        Map<String, UserAiQuotaEntity> quotasByFeatureCode = userAiQuotaMapper.selectList(
                        new LambdaQueryWrapper<UserAiQuotaEntity>()
                                .eq(UserAiQuotaEntity::getClerkUserId, clerkUserId)
                                .in(UserAiQuotaEntity::getFeatureCode, PLAN_FEATURE_CODES))
                .stream()
                .filter(quota -> quota.getFeatureCode() != null)
                .collect(LinkedHashMap::new, (map, quota) -> map.put(quota.getFeatureCode(), quota), Map::putAll);

        for (String featureCode : PLAN_FEATURE_CODES) {
            UserAiQuotaEntity quota = quotasByFeatureCode.get(featureCode);
            if (quota == null) {
                continue;
            }
            refreshResolvedPlanQuotaIfNeeded(clerkUserId, featureCode, now, subscription, plan, billingInterval, quota);
        }
    }

    private void refreshResolvedPlanQuotaIfNeeded(
            String clerkUserId,
            String featureCode,
            LocalDateTime now,
            UserSubscriptionEntity subscription,
            SubscriptionPlanEntity plan,
            String billingInterval,
            UserAiQuotaEntity quota) {
        LocalDateTime currentPlanEnd = quota.getPlanPeriodEnd();
        boolean missingPlanWindow = quota.getPlanPeriodStart() == null || currentPlanEnd == null;
        boolean planWindowExpired = currentPlanEnd != null && now.isAfter(currentPlanEnd);
        if (!missingPlanWindow && !planWindowExpired) {
            return;
        }

        if ("year".equals(billingInterval)) {
            refreshAnnualPlanQuota(clerkUserId, subscription, quota, featureCode, plan, now);
            return;
        }

        if ("month".equals(billingInterval) && planWindowExpired) {
            expireMonthlyPlanQuota(clerkUserId, subscription, quota, featureCode, now);
        }
    }

    private void applyPlanGrant(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant quotaPeriodStart,
            Instant quotaPeriodEnd,
            String invoiceId,
            String ledgerType,
            boolean additive) {
        SubscriptionPlanEntity plan = requirePlan(planCode);
        LocalDateTime periodStart = LocalDateTime.ofInstant(quotaPeriodStart, ZoneOffset.UTC);
        LocalDateTime periodEnd = LocalDateTime.ofInstant(quotaPeriodEnd, ZoneOffset.UTC);
        LocalDateTime now = LocalDateTime.now();

        for (FeatureGrant featureGrant : featureGrants(plan)) {
            String idempotencyKey = "invoice:" + invoiceId + ":"
                    + (additive ? "upgrade" : "plan") + ":" + featureGrant.featureCode();
            if (hasLedger(featureGrant.featureCode(), ledgerType, idempotencyKey)) {
                continue;
            }

            UserAiQuotaEntity quota = findOrCreateQuota(clerkUserId, featureGrant.featureCode(), now);
            long currentPlanBalance = quota.getPlanBalance() != null ? quota.getPlanBalance() : 0L;
            long newPlanBalance = additive ? currentPlanBalance + featureGrant.amount() : featureGrant.amount();

            quota.setPlanBalance(newPlanBalance);
            quota.setPlanPeriodStart(periodStart);
            quota.setPlanPeriodEnd(periodEnd);
            quota.setUpdatedAt(now);
            persistQuota(quota, now);

            QuotaLedgerEntity ledger = new QuotaLedgerEntity();
            ledger.setLedgerNo(generateLedgerNo());
            ledger.setClerkUserId(clerkUserId);
            ledger.setFeatureCode(featureGrant.featureCode());
            ledger.setLedgerType(ledgerType);
            ledger.setAmount(additive ? featureGrant.amount() : newPlanBalance);
            ledger.setSourceType("invoice");
            ledger.setSourceId(invoiceId);
            ledger.setIdempotencyKey(idempotencyKey);
            ledger.setSubscriptionId(subscriptionId);
            ledger.setInvoiceId(invoiceId);
            ledger.setFreeBalanceAfter(quota.getFreeBalance());
            ledger.setPlanBalanceAfter(newPlanBalance);
            ledger.setPaidBalanceAfter(quota.getPaidBalance());
            ledger.setBizContext(GSON.toJson(Map.of(
                    "plan_code", planCode,
                    "subscription_id", subscriptionId,
                    "quota_period_start", periodStart.toString(),
                    "quota_period_end", periodEnd.toString()
            )));
            ledger.setCreatedAt(now);
            quotaLedgerMapper.insert(ledger);
        }
    }

    private void refreshAnnualPlanQuota(
            String clerkUserId,
            UserSubscriptionEntity subscription,
            UserAiQuotaEntity quota,
            String featureCode,
            SubscriptionPlanEntity plan,
            LocalDateTime now) {
        if (subscription.getQuotaPeriodStart() == null) {
            return;
        }

        LocalDateTime anchor = subscription.getQuotaPeriodStart();
        LocalDateTime windowStart = anchor;
        while (!windowStart.plusMonths(1).isAfter(now)) {
            windowStart = windowStart.plusMonths(1);
        }
        LocalDateTime windowEnd = windowStart.plusMonths(1);

        if (windowStart.equals(quota.getPlanPeriodStart()) && windowEnd.equals(quota.getPlanPeriodEnd())) {
            return;
        }

        long grantAmount = featureGrantAmount(plan, featureCode);
        String idempotencyKey = "plan-refresh:" + clerkUserId + ":" + featureCode + ":" + windowStart;
        if (hasLedger(featureCode, LEDGER_TYPE_PLAN_REFRESH, idempotencyKey)) {
            return;
        }

        quota.setPlanBalance(grantAmount);
        quota.setPlanPeriodStart(windowStart);
        quota.setPlanPeriodEnd(windowEnd);
        quota.setUpdatedAt(now);
        persistQuota(quota, now);

        QuotaLedgerEntity ledger = new QuotaLedgerEntity();
        ledger.setLedgerNo(generateLedgerNo());
        ledger.setClerkUserId(clerkUserId);
        ledger.setFeatureCode(featureCode);
        ledger.setLedgerType(LEDGER_TYPE_PLAN_REFRESH);
        ledger.setAmount(grantAmount);
        ledger.setSourceType("subscription");
        ledger.setSourceId(subscription.getStripeSubscriptionId());
        ledger.setIdempotencyKey(idempotencyKey);
        ledger.setSubscriptionId(subscription.getStripeSubscriptionId());
        ledger.setFreeBalanceAfter(quota.getFreeBalance());
        ledger.setPlanBalanceAfter(grantAmount);
        ledger.setPaidBalanceAfter(quota.getPaidBalance());
        ledger.setBizContext(GSON.toJson(Map.of(
                "plan_code", subscription.getPlanCode(),
                "quota_period_start", windowStart.toString(),
                "quota_period_end", windowEnd.toString(),
                "refresh_mode", "annual_monthly_window"
        )));
        ledger.setCreatedAt(now);
        quotaLedgerMapper.insert(ledger);
    }

    private void expireMonthlyPlanQuota(
            String clerkUserId,
            UserSubscriptionEntity subscription,
            UserAiQuotaEntity quota,
            String featureCode,
            LocalDateTime now) {
        long previousPlanBalance = quota.getPlanBalance() != null ? quota.getPlanBalance() : 0L;
        LocalDateTime previousPlanEnd = quota.getPlanPeriodEnd();
        String idempotencyKey = "plan-expired:" + clerkUserId + ":" + featureCode + ":" + previousPlanEnd;
        Integer currentVersion = quota.getVersion();
        UpdateWrapper<UserAiQuotaEntity> updateWrapper = new UpdateWrapper<UserAiQuotaEntity>()
                .eq("id", quota.getId())
                .set("plan_balance", 0L)
                .set("plan_period_start", null)
                .set("plan_period_end", null)
                .set("updated_at", now)
                .setSql("version = version + 1");
        if (currentVersion != null) {
            updateWrapper.eq("version", currentVersion);
        }
        int updated = userAiQuotaMapper.update(null, updateWrapper);
        if (updated != 1) {
            throw new IllegalStateException("Quota update conflict during expire monthly plan quota: quotaId=" + quota.getId());
        }
        quota.setPlanBalance(0L);
        quota.setPlanPeriodStart(null);
        quota.setPlanPeriodEnd(null);
        quota.setVersion(currentVersion == null ? 1 : currentVersion + 1);

        if (hasLedger(featureCode, LEDGER_TYPE_PLAN_EXPIRED, idempotencyKey)) {
            return;
        }

        QuotaLedgerEntity ledger = new QuotaLedgerEntity();
        ledger.setLedgerNo(generateLedgerNo());
        ledger.setClerkUserId(clerkUserId);
        ledger.setFeatureCode(featureCode);
        ledger.setLedgerType(LEDGER_TYPE_PLAN_EXPIRED);
        ledger.setAmount(-previousPlanBalance);
        ledger.setSourceType("subscription");
        ledger.setSourceId(subscription.getStripeSubscriptionId());
        ledger.setIdempotencyKey(idempotencyKey);
        ledger.setSubscriptionId(subscription.getStripeSubscriptionId());
        ledger.setFreeBalanceAfter(quota.getFreeBalance());
        ledger.setPlanBalanceAfter(0L);
        ledger.setPaidBalanceAfter(quota.getPaidBalance());
        ledger.setBizContext(GSON.toJson(Map.of(
                "plan_code", subscription.getPlanCode(),
                "quota_period_end", String.valueOf(previousPlanEnd),
                "expire_mode", "lazy_monthly_guardrail"
        )));
        ledger.setCreatedAt(now);
        quotaLedgerMapper.insert(ledger);
    }

    private SubscriptionPlanEntity requirePlan(String planCode) {
        SubscriptionPlanEntity plan = subscriptionPlanMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPlanEntity>()
                        .eq(SubscriptionPlanEntity::getPlanCode, planCode)
                        .eq(SubscriptionPlanEntity::getIsActive, true)
                        .last("LIMIT 1"));
        if (plan == null) {
            throw new IllegalArgumentException("Unknown active plan code: " + planCode);
        }
        return plan;
    }

    private List<FeatureGrant> featureGrants(SubscriptionPlanEntity plan) {
        List<FeatureGrant> grants = new ArrayList<>();
        grants.add(new FeatureGrant(FeatureCode.TASK_CREATE.getCode(), defaultLong(plan.getAssignmentQuota())));
        grants.add(new FeatureGrant(FeatureCode.AI_DETECTION.getCode(), defaultLong(plan.getDetectionQuota())));
        grants.add(new FeatureGrant(FeatureCode.HUMANIZER.getCode(), defaultLong(plan.getHumanizerQuota())));
        return grants;
    }

    private long featureGrantAmount(SubscriptionPlanEntity plan, String featureCode) {
        return switch (featureCode) {
            case "task_create" -> defaultLong(plan.getAssignmentQuota());
            case "ai_detection" -> defaultLong(plan.getDetectionQuota());
            case "humanizer" -> defaultLong(plan.getHumanizerQuota());
            default -> throw new IllegalArgumentException("Unsupported feature for plan refresh: " + featureCode);
        };
    }

    private UserAiQuotaEntity findOrCreateQuota(String clerkUserId, String featureCode, LocalDateTime now) {
        UserAiQuotaEntity quota = userAiQuotaMapper.selectOne(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, clerkUserId)
                        .eq(UserAiQuotaEntity::getFeatureCode, featureCode)
                        .last("LIMIT 1"));
        if (quota != null) {
            return quota;
        }

        AiFeatureDefsEntity featureDef = aiFeatureDefsMapper.selectOne(
                new LambdaQueryWrapper<AiFeatureDefsEntity>()
                        .eq(AiFeatureDefsEntity::getFeatureCode, featureCode)
                        .eq(AiFeatureDefsEntity::getIsActive, true)
                        .last("LIMIT 1"));

        quota = new UserAiQuotaEntity();
        quota.setClerkUserId(clerkUserId);
        quota.setFeatureCode(featureCode);
        quota.setFreeBalance(featureDef != null && featureDef.getFreeQuotaAmount() != null
                ? featureDef.getFreeQuotaAmount() : 0L);
        quota.setFreePeriodStart(now);
        quota.setFreePeriodEnd(computePeriodEnd(now, featureDef != null ? featureDef.getFreeQuotaPeriod() : "monthly"));
        quota.setPlanBalance(0L);
        quota.setPaidBalance(0L);
        quota.setVersion(0);
        quota.setCreatedAt(now);
        quota.setUpdatedAt(now);
        userAiQuotaMapper.insert(quota);
        return quota;
    }

    private boolean hasLedger(String featureCode, String ledgerType, String idempotencyKey) {
        return quotaLedgerMapper.selectOne(
                new LambdaQueryWrapper<QuotaLedgerEntity>()
                        .eq(QuotaLedgerEntity::getFeatureCode, featureCode)
                        .eq(QuotaLedgerEntity::getLedgerType, ledgerType)
                        .eq(QuotaLedgerEntity::getIdempotencyKey, idempotencyKey)
                        .last("LIMIT 1")) != null;
    }

    private void persistQuota(UserAiQuotaEntity quota, LocalDateTime now) {
        if (quota.getId() == null) {
            quota.setCreatedAt(now);
            quota.setUpdatedAt(now);
            userAiQuotaMapper.insert(quota);
            return;
        }
        updateQuotaOrThrow(quota, "plan quota grant");
    }

    private void updateQuotaOrThrow(UserAiQuotaEntity quota, String action) {
        int updated = userAiQuotaMapper.updateById(quota);
        if (updated != 1) {
            throw new IllegalStateException("Quota update conflict during " + action + ": quotaId=" + quota.getId());
        }
    }

    private void clearPlanWindowOrThrow(UserAiQuotaEntity quota, LocalDateTime now) {
        Integer currentVersion = quota.getVersion();
        UpdateWrapper<UserAiQuotaEntity> updateWrapper = new UpdateWrapper<UserAiQuotaEntity>()
                .eq("id", quota.getId())
                .set("plan_balance", 0L)
                .set("plan_period_start", null)
                .set("plan_period_end", null)
                .set("updated_at", now)
                .setSql("version = version + 1");
        if (currentVersion != null) {
            updateWrapper.eq("version", currentVersion);
        }
        int updated = userAiQuotaMapper.update(null, updateWrapper);
        if (updated != 1) {
            throw new IllegalStateException("Quota update conflict during clear plan quota: quotaId=" + quota.getId());
        }
        quota.setVersion(currentVersion == null ? 1 : currentVersion + 1);
    }

    private long defaultLong(Long value) {
        return value != null ? value : 0L;
    }

    private String normalizeBillingInterval(String billingInterval) {
        if (billingInterval == null || billingInterval.isBlank()) {
            return "month";
        }
        return billingInterval.trim().toLowerCase();
    }

    private LocalDateTime computePeriodEnd(LocalDateTime start, String period) {
        if ("daily".equalsIgnoreCase(period)) {
            return start.plusDays(1);
        }
        if ("weekly".equalsIgnoreCase(period)) {
            return start.plusWeeks(1);
        }
        return start.plusMonths(1);
    }

    private String generateLedgerNo() {
        return "QL" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) +
                UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private record FeatureGrant(String featureCode, long amount) {
    }
}
