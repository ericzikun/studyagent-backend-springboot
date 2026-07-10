package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.Gson;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.model.testhelpers.TestClock;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.QuotaLedgerEntity;
import com.studyagent.infra.entity.UserAddonGrantEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.QuotaLedgerMapper;
import com.studyagent.infra.mapper.UserAddonGrantMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.quota.AddonGrantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AddonGrantServiceImpl implements AddonGrantService {
    private static final Gson GSON = new Gson();
    private static final String LEDGER_TYPE_ADDON_GRANT = "addon_grant";
    private static final String LEDGER_TYPE_ADDON_EXPIRED = "addon_expired";
    private static final String LEDGER_TYPE_ADDON_PAUSE = "addon_pause";
    private static final String LEDGER_TYPE_ADDON_RESUME = "addon_resume";

    private final AddonPackageDefMapper addonPackageDefMapper;
    private final UserAddonGrantMapper userAddonGrantMapper;
    private final QuotaLedgerMapper quotaLedgerMapper;
    private final UserSubscriptionMapper userSubscriptionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grantFromPaidCheckout(
            String clerkUserId,
            String addonCode,
            String stripeSessionId,
            String paymentIntentId,
            Instant paidAt) {
        UserAddonGrantEntity existing = userAddonGrantMapper.selectOne(
                new LambdaQueryWrapper<UserAddonGrantEntity>()
                        .eq(UserAddonGrantEntity::getStripeSessionId, stripeSessionId)
                        .last("LIMIT 1"));
        if (existing != null) {
            return;
        }

        AddonPackageDefEntity addon = addonPackageDefMapper.selectOne(
                new LambdaQueryWrapper<AddonPackageDefEntity>()
                        .eq(AddonPackageDefEntity::getAddonCode, addonCode)
                        .eq(AddonPackageDefEntity::getIsActive, true)
                        .last("LIMIT 1"));
        if (addon == null) {
            throw new IllegalArgumentException("Unknown active addon code: " + addonCode);
        }

        LocalDateTime purchaseTime = DateTimeFormats.fromInstant(paidAt);
        UserAddonGrantEntity grant = new UserAddonGrantEntity();
        grant.setClerkUserId(clerkUserId);
        grant.setFeatureCode(addon.getFeatureCode());
        grant.setGrantType("addon");
        grant.setAddonCode(addonCode);
        grant.setStatus("active");
        grant.setInitialAmount(defaultLong(addon.getQuotaAmount()));
        grant.setRemainingAmount(defaultLong(addon.getQuotaAmount()));
        grant.setStripeSessionId(stripeSessionId);
        grant.setStripePaymentIntentId(paymentIntentId);
        grant.setPurchasedAt(purchaseTime);
        grant.setExpiresAt(purchaseTime.plusMonths(Math.max(1, defaultInt(addon.getValidityMonths(), 2))));
        grant.setVersion(0);
        grant.setCreatedAt(DateTimeFormats.now());
        grant.setUpdatedAt(DateTimeFormats.now());
        userAddonGrantMapper.insert(grant);

        QuotaLedgerEntity ledger = new QuotaLedgerEntity();
        ledger.setLedgerNo(generateLedgerNo());
        ledger.setClerkUserId(clerkUserId);
        ledger.setFeatureCode(addon.getFeatureCode());
        ledger.setLedgerType(LEDGER_TYPE_ADDON_GRANT);
        ledger.setAmount(defaultLong(addon.getQuotaAmount()));
        ledger.setSourceType("checkout");
        ledger.setSourceId(stripeSessionId);
        ledger.setIdempotencyKey("checkout:" + stripeSessionId + ":addon");
        ledger.setAddonBalanceAfter(sumActiveAddonBalance(clerkUserId, addon.getFeatureCode(), DateTimeFormats.now()));
        ledger.setBizContext(GSON.toJson(Map.of(
                "addon_code", addonCode,
                "stripe_session_id", stripeSessionId,
                "stripe_payment_intent_id", paymentIntentId == null ? "" : paymentIntentId
        )));
        ledger.setCreatedAt(DateTimeFormats.now());
        quotaLedgerMapper.insert(ledger);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void expireEligible(String clerkUserId, String featureCode, String trigger) {
        if (clerkUserId == null || clerkUserId.isBlank()
                || featureCode == null || featureCode.isBlank()) {
            return;
        }
        LocalDateTime now = resolveNow(clerkUserId, DateTimeFormats.now());
        List<UserAddonGrantEntity> grants = userAddonGrantMapper.selectList(
                new LambdaQueryWrapper<UserAddonGrantEntity>()
                        .eq(UserAddonGrantEntity::getClerkUserId, clerkUserId)
                        .eq(UserAddonGrantEntity::getFeatureCode, featureCode)
                        .eq(UserAddonGrantEntity::getGrantType, "addon")
                        .in(UserAddonGrantEntity::getStatus, List.of("active", "paused"))
                        .le(UserAddonGrantEntity::getExpiresAt, now)
                        .gt(UserAddonGrantEntity::getRemainingAmount, 0)
                        .orderByAsc(UserAddonGrantEntity::getExpiresAt)
                        .orderByAsc(UserAddonGrantEntity::getId));
        for (UserAddonGrantEntity grant : grants) {
            expireGrantIfNeeded(grant, now, trigger);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pauseAll(String clerkUserId, String subscriptionId, String idempotencyKey) {
        if (hasLedger(LEDGER_TYPE_ADDON_PAUSE, idempotencyKey)) {
            return;
        }
        LocalDateTime now = resolveNow(clerkUserId, DateTimeFormats.now());
        List<UserAddonGrantEntity> grants = userAddonGrantMapper.selectList(
                new LambdaQueryWrapper<UserAddonGrantEntity>()
                        .eq(UserAddonGrantEntity::getClerkUserId, clerkUserId)
                        .eq(UserAddonGrantEntity::getGrantType, "addon")
                        .eq(UserAddonGrantEntity::getStatus, "active")
                        .gt(UserAddonGrantEntity::getRemainingAmount, 0));
        for (UserAddonGrantEntity grant : grants) {
            if (grant.getExpiresAt() != null && !grant.getExpiresAt().isAfter(now)) {
                expireGrantIfNeeded(grant, now, "subscription_pause");
            } else {
                grant.setStatus("paused");
                grant.setPausedAt(now);
                grant.setUpdatedAt(now);
                updateGrantOrThrow(grant, "pause add-on grants");
            }
        }
        insertLifecycleLedger(clerkUserId, subscriptionId, idempotencyKey, LEDGER_TYPE_ADDON_PAUSE, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resumeEligible(String clerkUserId, String subscriptionId, String idempotencyKey) {
        if (hasLedger(LEDGER_TYPE_ADDON_RESUME, idempotencyKey)) {
            return;
        }
        LocalDateTime now = resolveNow(clerkUserId, DateTimeFormats.now());
        List<UserAddonGrantEntity> grants = userAddonGrantMapper.selectList(
                new LambdaQueryWrapper<UserAddonGrantEntity>()
                        .eq(UserAddonGrantEntity::getClerkUserId, clerkUserId)
                        .eq(UserAddonGrantEntity::getGrantType, "addon")
                        .eq(UserAddonGrantEntity::getStatus, "paused")
                        .gt(UserAddonGrantEntity::getRemainingAmount, 0));
        for (UserAddonGrantEntity grant : grants) {
            if (grant.getExpiresAt() != null && !grant.getExpiresAt().isAfter(now)) {
                expireGrantIfNeeded(grant, now, "subscription_resume");
            } else {
                grant.setStatus("active");
                grant.setPausedAt(null);
                grant.setUpdatedAt(now);
                resumeGrantOrThrow(grant, now);
            }
        }
        insertLifecycleLedger(clerkUserId, subscriptionId, idempotencyKey, LEDGER_TYPE_ADDON_RESUME, now);
    }

    private boolean hasLedger(String ledgerType, String idempotencyKey) {
        return quotaLedgerMapper.selectOne(
                new LambdaQueryWrapper<QuotaLedgerEntity>()
                        .eq(QuotaLedgerEntity::getLedgerType, ledgerType)
                        .eq(QuotaLedgerEntity::getIdempotencyKey, idempotencyKey)
                        .last("LIMIT 1")) != null;
    }

    private void insertLifecycleLedger(
            String clerkUserId,
            String subscriptionId,
            String idempotencyKey,
            String ledgerType,
            LocalDateTime now) {
        QuotaLedgerEntity ledger = new QuotaLedgerEntity();
        ledger.setLedgerNo(generateLedgerNo());
        ledger.setClerkUserId(clerkUserId);
        ledger.setFeatureCode("addon");
        ledger.setLedgerType(ledgerType);
        ledger.setAmount(0L);
        ledger.setSourceType("subscription");
        ledger.setSourceId(subscriptionId);
        ledger.setIdempotencyKey(idempotencyKey);
        ledger.setSubscriptionId(subscriptionId);
        ledger.setCreatedAt(now);
        quotaLedgerMapper.insert(ledger);
    }

    private void expireGrantIfNeeded(UserAddonGrantEntity grant, LocalDateTime now, String trigger) {
        if (grant == null) {
            return;
        }
        String previousStatus = grant.getStatus();
        grant.setStatus("expired");
        grant.setPausedAt(null);
        grant.setUpdatedAt(now);
        updateGrantOrThrow(grant, "expire add-on grant");

        long expiredAmount = defaultLong(grant.getRemainingAmount());
        if (expiredAmount <= 0 || hasLedger(LEDGER_TYPE_ADDON_EXPIRED, expireIdempotencyKey(grant))) {
            return;
        }

        QuotaLedgerEntity ledger = new QuotaLedgerEntity();
        ledger.setLedgerNo(generateLedgerNo());
        ledger.setClerkUserId(grant.getClerkUserId());
        ledger.setFeatureCode(grant.getFeatureCode());
        ledger.setLedgerType(LEDGER_TYPE_ADDON_EXPIRED);
        ledger.setAmount(-expiredAmount);
        ledger.setSourceType("system");
        ledger.setSourceId(String.valueOf(grant.getId()));
        ledger.setIdempotencyKey(expireIdempotencyKey(grant));
        ledger.setAddonBalanceAfter(sumActiveAddonBalance(grant.getClerkUserId(), grant.getFeatureCode(), now));
        ledger.setBizContext(GSON.toJson(Map.of(
                "grant_id", grant.getId(),
                "addon_code", grant.getAddonCode() == null ? "" : grant.getAddonCode(),
                "grant_type", grant.getGrantType() == null ? "" : grant.getGrantType(),
                "expired_amount", expiredAmount,
                "expires_at", grant.getExpiresAt() == null ? "" : grant.getExpiresAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                "previous_status", previousStatus == null ? "" : previousStatus,
                "trigger", trigger == null ? "" : trigger
        )));
        ledger.setCreatedAt(now);
        quotaLedgerMapper.insert(ledger);
    }

    private String expireIdempotencyKey(UserAddonGrantEntity grant) {
        return "addon-expire:" + grant.getId();
    }

    LocalDateTime resolveNow(String clerkUserId, LocalDateTime fallbackNow) {
        if (userSubscriptionMapper == null || clerkUserId == null || clerkUserId.isBlank()) {
            return fallbackNow;
        }
        UserSubscriptionEntity subscription = userSubscriptionMapper.selectByUser(clerkUserId);
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
            return fallbackNow;
        }
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

    private void updateGrantOrThrow(UserAddonGrantEntity grant, String action) {
        int updated = userAddonGrantMapper.updateById(grant);
        if (updated != 1) {
            throw new IllegalStateException("Addon grant update conflict during " + action + ": grantId=" + grant.getId());
        }
    }

    private void resumeGrantOrThrow(UserAddonGrantEntity grant, LocalDateTime now) {
        Integer currentVersion = grant.getVersion();
        UpdateWrapper<UserAddonGrantEntity> updateWrapper = new UpdateWrapper<UserAddonGrantEntity>()
                .eq("id", grant.getId())
                .set("status", grant.getStatus())
                .set("paused_at", null)
                .set("updated_at", now)
                .setSql("version = version + 1");
        if (currentVersion != null) {
            updateWrapper.eq("version", currentVersion);
        }
        int updated = userAddonGrantMapper.update(null, updateWrapper);
        if (updated != 1) {
            throw new IllegalStateException("Addon grant update conflict during resume: grantId=" + grant.getId());
        }
    }

    private long sumActiveAddonBalance(String clerkUserId, String featureCode, LocalDateTime now) {
        return userAddonGrantMapper.selectList(
                        new LambdaQueryWrapper<UserAddonGrantEntity>()
                                .eq(UserAddonGrantEntity::getClerkUserId, clerkUserId)
                                .eq(UserAddonGrantEntity::getFeatureCode, featureCode)
                                .eq(UserAddonGrantEntity::getStatus, "active")
                                .and(wrapper -> wrapper
                                        .gt(UserAddonGrantEntity::getExpiresAt, now)
                                        .or()
                                        .isNull(UserAddonGrantEntity::getExpiresAt))
                                .gt(UserAddonGrantEntity::getRemainingAmount, 0))
                .stream()
                .map(UserAddonGrantEntity::getRemainingAmount)
                .filter(value -> value != null && value > 0)
                .mapToLong(Long::longValue)
                .sum();
    }

    private long defaultLong(Long value) {
        return value != null ? value : 0L;
    }

    private int defaultInt(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private String generateLedgerNo() {
        return "QL" + DateTimeFormats.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) +
                UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}
