package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.Gson;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.model.testhelpers.TestClock;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.common.datetime.DateTimeFormats;
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
import com.studyagent.service.application.verla.quota.QuotaBusinessMetrics;
import com.studyagent.service.domain.billing.BillingEntitlementPolicy;
import com.studyagent.service.domain.billing.IntroTrialPlans;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
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
    private static final String GRANT_TYPE_SUBSCRIPTION_INITIAL = "subscription_initial";
    private static final String GRANT_TYPE_SUBSCRIPTION_RENEWAL = "subscription_renewal";
    private static final String GRANT_TYPE_SUBSCRIPTION_UPGRADE = "subscription_upgrade";

    private final SubscriptionPlanMapper subscriptionPlanMapper;
    private final AiFeatureDefsMapper aiFeatureDefsMapper;
    private final UserAiQuotaMapper userAiQuotaMapper;
    private final QuotaLedgerMapper quotaLedgerMapper;
    private final UserSubscriptionMapper userSubscriptionMapper;

    @Autowired
    private QuotaGrantAnalyticsPublisher quotaGrantAnalyticsPublisher;

    @Autowired
    private QuotaBusinessMetrics quotaBusinessMetrics;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshPlanQuotaIfNeeded(String clerkUserId, String featureCode) {
        LocalDateTime fallbackNow = DateTimeFormats.now();
        UserSubscriptionEntity subscription = userSubscriptionMapper.selectByUser(clerkUserId);
        refreshPlanQuotaIfNeeded(clerkUserId, featureCode, resolveRefreshTime(subscription, fallbackNow));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshAllPlanQuotasIfNeeded(String clerkUserId) {
        LocalDateTime fallbackNow = DateTimeFormats.now();
        UserSubscriptionEntity subscription = userSubscriptionMapper.selectByUser(clerkUserId);
        refreshAllPlanQuotasIfNeeded(clerkUserId, resolveRefreshTime(subscription, fallbackNow));
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
        resetFromPaidInvoice(
                clerkUserId,
                subscriptionId,
                planCode,
                quotaPeriodStart,
                quotaPeriodEnd,
                invoiceId,
                GRANT_TYPE_SUBSCRIPTION_RENEWAL);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetFromPaidInvoice(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant quotaPeriodStart,
            Instant quotaPeriodEnd,
            String invoiceId,
            String grantType) {
        applyPlanGrant(
                clerkUserId,
                subscriptionId,
                planCode,
                quotaPeriodStart,
                quotaPeriodEnd,
                invoiceId,
                LEDGER_TYPE_PLAN_RESET,
                false,
                normalizeResetGrantType(grantType));
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
                true,
                GRANT_TYPE_SUBSCRIPTION_UPGRADE);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grantUpgradeFromCheckout(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant quotaPeriodStart,
            Instant quotaPeriodEnd,
            String upgradeOrderNo) {
        applyPlanGrantFromSource(
                clerkUserId,
                subscriptionId,
                planCode,
                quotaPeriodStart,
                quotaPeriodEnd,
                upgradeOrderNo,
                "checkout_upgrade",
                "checkout-upgrade",
                LEDGER_TYPE_UPGRADE_GRANT,
                true,
                GRANT_TYPE_SUBSCRIPTION_UPGRADE);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearPlanQuota(String clerkUserId, String subscriptionId, String planCode, String idempotencyKey) {
        List<UserAiQuotaEntity> quotas = userAiQuotaMapper.selectList(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, clerkUserId)
                        .in(UserAiQuotaEntity::getFeatureCode, List.of(
                                FeatureCode.TASK_CREATE.getCode(),
                                FeatureCode.AI_DETECTION.getCode(),
                                FeatureCode.HUMANIZER.getCode())));
        LocalDateTime now = resolveRefreshTime(subscriptionId, DateTimeFormats.now());
        UserSubscriptionEntity subscription = findSubscriptionForPlanLifecycle(clerkUserId, subscriptionId);
        String effectivePlanCode = resolveLifecyclePlanCode(planCode, subscription);
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
            if (effectivePlanCode != null) {
                ledger.setBizContext(GSON.toJson(Map.of(
                        "plan_code", effectivePlanCode,
                        "subscription_id", subscriptionId == null ? "" : subscriptionId
                )));
            }
            ledger.setCreatedAt(now);
            quotaLedgerMapper.insert(ledger);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    void refreshPlanQuotaIfNeeded(String clerkUserId, String featureCode, LocalDateTime now) {
        if (clerkUserId == null || clerkUserId.isBlank() || featureCode == null || featureCode.isBlank()) {
            return;
        }

        UserSubscriptionEntity subscription = userSubscriptionMapper.selectByUser(clerkUserId);
        if (subscription == null
                || !BillingEntitlementPolicy.allowsPlanRefresh(subscription.getStatus())
                || subscription.getPlanCode() == null
                || subscription.getPlanCode().isBlank()) {
            return;
        }

        UserAiQuotaEntity quota = userAiQuotaMapper.selectOne(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, clerkUserId)
                        .eq(UserAiQuotaEntity::getFeatureCode, featureCode)
                        .last("LIMIT 1"));
        if (quota != null && !needsPlanRefresh(quota, now)) {
            return;
        }

        UserSubscriptionEntity lockedSubscription = userSubscriptionMapper.selectByUserForUpdate(clerkUserId);
        if (lockedSubscription == null
                || !BillingEntitlementPolicy.allowsPlanRefresh(lockedSubscription.getStatus())
                || lockedSubscription.getPlanCode() == null
                || lockedSubscription.getPlanCode().isBlank()) {
            return;
        }

        UserAiQuotaEntity lockedQuota = userAiQuotaMapper.selectOne(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, clerkUserId)
                        .eq(UserAiQuotaEntity::getFeatureCode, featureCode)
                        .last("LIMIT 1"));
        if (lockedQuota == null) {
            lockedQuota = findOrCreateQuota(clerkUserId, featureCode, now);
        } else if (!needsPlanRefresh(lockedQuota, now)) {
            return;
        }

        SubscriptionPlanEntity plan = requirePlan(lockedSubscription.getPlanCode());
        String billingInterval = resolveQuotaBillingInterval(plan);
        refreshResolvedPlanQuotaIfNeeded(
                clerkUserId,
                featureCode,
                now,
                lockedSubscription,
                plan,
                billingInterval,
                lockedQuota);
    }

    @Transactional(rollbackFor = Exception.class)
    void refreshAllPlanQuotasIfNeeded(String clerkUserId, LocalDateTime now) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return;
        }

        UserSubscriptionEntity subscription = userSubscriptionMapper.selectByUser(clerkUserId);
        if (subscription == null
                || !BillingEntitlementPolicy.allowsPlanRefresh(subscription.getStatus())
                || subscription.getPlanCode() == null
                || subscription.getPlanCode().isBlank()) {
            return;
        }

        Map<String, UserAiQuotaEntity> quotasByFeatureCode = quotasByFeatureCode(clerkUserId);
        boolean missingQuotaRows = quotasByFeatureCode.size() < PLAN_FEATURE_CODES.size();
        boolean anyQuotaNeedsRefresh = missingQuotaRows || quotasByFeatureCode.values().stream()
                .anyMatch(quota -> needsPlanRefresh(quota, now));
        if (!anyQuotaNeedsRefresh) {
            return;
        }

        UserSubscriptionEntity lockedSubscription = userSubscriptionMapper.selectByUserForUpdate(clerkUserId);
        if (lockedSubscription == null
                || !BillingEntitlementPolicy.allowsPlanRefresh(lockedSubscription.getStatus())
                || lockedSubscription.getPlanCode() == null
                || lockedSubscription.getPlanCode().isBlank()) {
            return;
        }

        SubscriptionPlanEntity plan = requirePlan(lockedSubscription.getPlanCode());
        String billingInterval = resolveQuotaBillingInterval(plan);

        Map<String, UserAiQuotaEntity> lockedQuotasByFeatureCode = quotasByFeatureCode(clerkUserId);
        for (String featureCode : PLAN_FEATURE_CODES) {
            UserAiQuotaEntity quota = lockedQuotasByFeatureCode.get(featureCode);
            if (quota == null) {
                quota = findOrCreateQuota(clerkUserId, featureCode, now);
                lockedQuotasByFeatureCode.put(featureCode, quota);
            }
            refreshResolvedPlanQuotaIfNeeded(clerkUserId, featureCode, now, lockedSubscription, plan, billingInterval, quota);
        }
    }

    private Map<String, UserAiQuotaEntity> quotasByFeatureCode(String clerkUserId) {
        return userAiQuotaMapper.selectList(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, clerkUserId)
                        .in(UserAiQuotaEntity::getFeatureCode, PLAN_FEATURE_CODES))
                .stream()
                .filter(quota -> quota.getFeatureCode() != null)
                .collect(LinkedHashMap::new, (map, quota) -> map.put(quota.getFeatureCode(), quota), Map::putAll);
    }

    private void refreshResolvedPlanQuotaIfNeeded(
            String clerkUserId,
            String featureCode,
            LocalDateTime now,
            UserSubscriptionEntity subscription,
            SubscriptionPlanEntity plan,
            String billingInterval,
            UserAiQuotaEntity quota) {
        if (!needsPlanRefresh(quota, now)) {
            return;
        }

        boolean missingPlanWindow = quota.getPlanPeriodStart() == null || quota.getPlanPeriodEnd() == null;
        LocalDateTime currentPlanEnd = quota.getPlanPeriodEnd();
        boolean planWindowExpired = currentPlanEnd != null && now.isAfter(currentPlanEnd);
        if ("year".equals(billingInterval)) {
            refreshAnnualPlanQuota(clerkUserId, subscription, quota, featureCode, plan, now);
            return;
        }

        // month and week share fixed-window refresh/expire semantics (window from subscription quota periods).
        if (("month".equals(billingInterval) || "week".equals(billingInterval)) && missingPlanWindow) {
            refreshMonthlyPlanQuota(clerkUserId, subscription, quota, featureCode, plan, now);
            return;
        }

        if (("month".equals(billingInterval) || "week".equals(billingInterval)) && planWindowExpired) {
            expireMonthlyPlanQuota(clerkUserId, subscription, quota, featureCode, now);
        }
    }

    private void refreshMonthlyPlanQuota(
            String clerkUserId,
            UserSubscriptionEntity subscription,
            UserAiQuotaEntity quota,
            String featureCode,
            SubscriptionPlanEntity plan,
            LocalDateTime now) {
        LocalDateTime windowStart = subscription.getQuotaPeriodStart();
        LocalDateTime windowEnd = subscription.getQuotaPeriodEnd();
        if (windowStart == null || windowEnd == null) {
            return;
        }
        String idempotencyKey = "plan-refresh:" + clerkUserId + ":" + featureCode + ":" + windowStart;
        if (hasLedger(featureCode, LEDGER_TYPE_PLAN_REFRESH, idempotencyKey)) {
            quota.setPlanPeriodStart(windowStart);
            quota.setPlanPeriodEnd(windowEnd);
            quota.setUpdatedAt(now);
            persistQuota(quota, now);
            return;
        }

        long grantAmount = featureGrantAmount(plan, featureCode);
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
                "refresh_mode", "monthly_missing_window"
        )));
        ledger.setCreatedAt(now);
        quotaLedgerMapper.insert(ledger);
        publishQuotaGrant(
                clerkUserId,
                GRANT_TYPE_SUBSCRIPTION_RENEWAL,
                featureCode,
                grantAmount,
                subscription.getPlanCode(),
                null,
                "subscription",
                subscription.getStripeSubscriptionId(),
                idempotencyKey,
                windowStart,
                windowEnd);
    }

    private void applyPlanGrant(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant quotaPeriodStart,
            Instant quotaPeriodEnd,
            String invoiceId,
            String ledgerType,
            boolean additive,
            String grantType) {
        applyPlanGrantFromSource(
                clerkUserId,
                subscriptionId,
                planCode,
                quotaPeriodStart,
                quotaPeriodEnd,
                invoiceId,
                "invoice",
                "invoice",
                ledgerType,
                additive,
                grantType);
    }

    private void applyPlanGrantFromSource(
            String clerkUserId,
            String subscriptionId,
            String planCode,
            Instant quotaPeriodStart,
            Instant quotaPeriodEnd,
            String sourceId,
            String sourceType,
            String idempotencyPrefix,
            String ledgerType,
            boolean additive,
            String grantType) {
        SubscriptionPlanEntity plan = requirePlan(planCode);
        LocalDateTime periodStart = DateTimeFormats.fromInstant(quotaPeriodStart);
        LocalDateTime periodEnd = DateTimeFormats.fromInstant(quotaPeriodEnd);
        LocalDateTime now = resolveRefreshTime(subscriptionId, DateTimeFormats.now());

        for (FeatureGrant featureGrant : featureGrants(plan)) {
            String idempotencyKey = idempotencyPrefix + ":" + sourceId + ":"
                    + (additive ? "upgrade" : "plan") + ":" + featureGrant.featureCode();
            if (hasLedger(featureGrant.featureCode(), ledgerType, idempotencyKey)) {
                continue;
            }
            try {
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
                ledger.setSourceType(sourceType);
                ledger.setSourceId(sourceId);
                ledger.setIdempotencyKey(idempotencyKey);
                ledger.setSubscriptionId(subscriptionId);
                ledger.setInvoiceId("invoice".equals(sourceType) ? sourceId : null);
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
                publishQuotaGrant(
                        clerkUserId,
                        grantType,
                        featureGrant.featureCode(),
                        featureGrant.amount(),
                        planCode,
                        null,
                        sourceType,
                        sourceId,
                        idempotencyKey,
                        periodStart,
                        periodEnd);
                if (quotaBusinessMetrics != null) {
                    quotaBusinessMetrics.recordGrant(
                            metricGrantType(sourceType, grantType), featureGrant.featureCode(), grantType, planCode,
                            QuotaBusinessMetrics.Result.SUCCESS);
                }
            } catch (RuntimeException ex) {
                if (quotaBusinessMetrics != null) {
                    quotaBusinessMetrics.recordGrant(
                            metricGrantType(sourceType, grantType), featureGrant.featureCode(), grantType, planCode,
                            QuotaBusinessMetrics.Result.ERROR);
                }
                throw ex;
            }
        }
    }

    private String metricGrantType(String sourceType, String grantType) {
        return "checkout_upgrade".equals(sourceType) ? "manual_upgrade" : grantType;
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

        syncAnnualSubscriptionQuotaWindow(subscription, windowStart, windowEnd, now);

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
        publishQuotaGrant(
                clerkUserId,
                GRANT_TYPE_SUBSCRIPTION_RENEWAL,
                featureCode,
                grantAmount,
                subscription.getPlanCode(),
                null,
                "subscription",
                subscription.getStripeSubscriptionId(),
                idempotencyKey,
                windowStart,
                windowEnd);
    }

    private String normalizeResetGrantType(String grantType) {
        if (GRANT_TYPE_SUBSCRIPTION_INITIAL.equals(grantType)
                || GRANT_TYPE_SUBSCRIPTION_RENEWAL.equals(grantType)) {
            return grantType;
        }
        log.warn("Unknown reset quota grantType={}, fallback={}",
                grantType, GRANT_TYPE_SUBSCRIPTION_RENEWAL);
        return GRANT_TYPE_SUBSCRIPTION_RENEWAL;
    }

    private void publishQuotaGrant(
            String clerkUserId,
            String grantType,
            String featureCode,
            long quotaAmount,
            String planCode,
            String addonCode,
            String sourceType,
            String sourceId,
            String idempotencyKey,
            LocalDateTime quotaPeriodStart,
            LocalDateTime quotaPeriodEnd) {
        if (quotaGrantAnalyticsPublisher == null || quotaAmount <= 0) {
            return;
        }
        quotaGrantAnalyticsPublisher.publishAfterCommit(new QuotaGrantAnalyticsEvent(
                clerkUserId,
                grantType,
                featureCode,
                quotaAmount,
                planCode,
                addonCode,
                sourceType,
                sourceId,
                idempotencyKey,
                quotaPeriodStart,
                quotaPeriodEnd));
    }

    private void syncAnnualSubscriptionQuotaWindow(
            UserSubscriptionEntity subscription,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            LocalDateTime now) {
        if (subscription == null || subscription.getClerkUserId() == null || subscription.getClerkUserId().isBlank()) {
            return;
        }
        if (windowStart.equals(subscription.getQuotaPeriodStart()) && windowEnd.equals(subscription.getQuotaPeriodEnd())) {
            return;
        }

        int updated = userSubscriptionMapper.update(
                null,
                new UpdateWrapper<UserSubscriptionEntity>()
                        .eq("clerk_user_id", subscription.getClerkUserId())
                        .set("quota_period_start", windowStart)
                        .set("quota_period_end", windowEnd)
                        .set("updated_at", now));
        if (updated != 1) {
            throw new IllegalStateException("Subscription update conflict during annual quota window sync: clerkUserId="
                    + subscription.getClerkUserId());
        }

        subscription.setQuotaPeriodStart(windowStart);
        subscription.setQuotaPeriodEnd(windowEnd);
        subscription.setUpdatedAt(now);
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
        // Existing subscribers keep their configured grants after new sales stop.
        SubscriptionPlanEntity plan = subscriptionPlanMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPlanEntity>()
                        .eq(SubscriptionPlanEntity::getPlanCode, planCode)
                        .last("LIMIT 1"));
        if (plan == null) {
            throw new IllegalArgumentException("Unknown plan code: " + planCode);
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

    private boolean needsPlanRefresh(UserAiQuotaEntity quota, LocalDateTime now) {
        LocalDateTime currentPlanEnd = quota.getPlanPeriodEnd();
        boolean missingPlanWindow = quota.getPlanPeriodStart() == null || currentPlanEnd == null;
        boolean planWindowExpired = currentPlanEnd != null && now.isAfter(currentPlanEnd);
        return missingPlanWindow || planWindowExpired;
    }

    LocalDateTime resolveRefreshTime(UserSubscriptionEntity subscription, LocalDateTime fallbackNow) {
        if (!shouldUseStripeSimulationTime(subscription)) {
            return fallbackNow;
        }
        try {
            Subscription stripeSubscription = retrieveStripeSubscription(subscription.getStripeSubscriptionId());
            if (stripeSubscription == null) {
                return fallbackNow;
            }
            String testClockId = stripeSubscription.getTestClock();
            if (testClockId == null || testClockId.isBlank()) {
                return fallbackNow;
            }
            Long frozenTime = retrieveTestClockFrozenTime(testClockId);
            if (frozenTime == null) {
                return fallbackNow;
            }
            LocalDateTime simulatedNow = DateTimeFormats.fromInstant(Instant.ofEpochSecond(frozenTime));
            return simulatedNow.isAfter(fallbackNow) ? simulatedNow : fallbackNow;
        } catch (StripeException e) {
            log.warn("Resolve Stripe test clock time failed for subscription {}", subscription.getStripeSubscriptionId(), e);
            return fallbackNow;
        }
    }

    LocalDateTime resolveRefreshTime(String subscriptionId, LocalDateTime fallbackNow) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return fallbackNow;
        }
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setStripeSubscriptionId(subscriptionId);
        return resolveRefreshTime(subscription, fallbackNow);
    }

    private UserSubscriptionEntity findSubscriptionForPlanLifecycle(String clerkUserId, String subscriptionId) {
        if (userSubscriptionMapper == null) {
            return null;
        }
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            UserSubscriptionEntity bySubscriptionId = userSubscriptionMapper.selectOne(
                    new LambdaQueryWrapper<UserSubscriptionEntity>()
                            .eq(UserSubscriptionEntity::getStripeSubscriptionId, subscriptionId)
                            .last("LIMIT 1"));
            if (bySubscriptionId != null) {
                return bySubscriptionId;
            }
        }
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return null;
        }
        return userSubscriptionMapper.selectByUser(clerkUserId);
    }

    private String resolveLifecyclePlanCode(String explicitPlanCode, UserSubscriptionEntity subscription) {
        if (explicitPlanCode != null && !explicitPlanCode.isBlank()) {
            return explicitPlanCode;
        }
        if (subscription == null) {
            return null;
        }
        String subscriptionPlanCode = subscription.getPlanCode();
        return subscriptionPlanCode == null || subscriptionPlanCode.isBlank() ? null : subscriptionPlanCode;
    }

    Subscription retrieveStripeSubscription(String subscriptionId) throws StripeException {
        return Subscription.retrieve(subscriptionId);
    }

    Long retrieveTestClockFrozenTime(String testClockId) throws StripeException {
        return TestClock.retrieve(testClockId).getFrozenTime();
    }

    private boolean shouldUseStripeSimulationTime(UserSubscriptionEntity subscription) {
        if (subscription == null || subscription.getStripeSubscriptionId() == null || subscription.getStripeSubscriptionId().isBlank()) {
            return false;
        }
        String apiKey = Stripe.apiKey;
        return apiKey != null && apiKey.startsWith("sk_test_");
    }

    private String normalizeBillingInterval(String billingInterval) {
        if (billingInterval == null || billingInterval.isBlank()) {
            return "month";
        }
        return billingInterval.trim().toLowerCase();
    }

    /**
     * Trial SKUs expose month/year for the frontend catalog, but quota windows follow
     * the fixed Stripe intro period (~7 days). Treat them as month/week fixed windows.
     */
    private String resolveQuotaBillingInterval(SubscriptionPlanEntity plan) {
        if (plan != null
                && IntroTrialPlans.isIntroTrialPlan(plan.getPlanCode(), plan.getOfferKind())) {
            return "week";
        }
        String interval = normalizeBillingInterval(plan == null ? null : plan.getBillingInterval());
        // One-time Pro Trial catalog interval is "once"; treat quota as a fixed window.
        if ("once".equals(interval)) {
            return "week";
        }
        return interval;
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
        return "QL" + DateTimeFormats.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) +
                UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private record FeatureGrant(String featureCode, long amount) {
    }
}
