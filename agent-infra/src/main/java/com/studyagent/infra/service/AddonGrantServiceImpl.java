package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.Gson;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.QuotaLedgerEntity;
import com.studyagent.infra.entity.UserAddonGrantEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.QuotaLedgerMapper;
import com.studyagent.infra.mapper.UserAddonGrantMapper;
import com.studyagent.service.domain.quota.AddonGrantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AddonGrantServiceImpl implements AddonGrantService {
    private static final Gson GSON = new Gson();
    private static final String LEDGER_TYPE_ADDON_GRANT = "addon_grant";
    private static final String LEDGER_TYPE_ADDON_PAUSE = "addon_pause";
    private static final String LEDGER_TYPE_ADDON_RESUME = "addon_resume";

    private final AddonPackageDefMapper addonPackageDefMapper;
    private final UserAddonGrantMapper userAddonGrantMapper;
    private final QuotaLedgerMapper quotaLedgerMapper;

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

        LocalDateTime purchaseTime = LocalDateTime.ofInstant(paidAt, ZoneOffset.UTC);
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
        grant.setCreatedAt(LocalDateTime.now());
        grant.setUpdatedAt(LocalDateTime.now());
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
        ledger.setAddonBalanceAfter(sumActiveAddonBalance(clerkUserId, addon.getFeatureCode(), LocalDateTime.now()));
        ledger.setBizContext(GSON.toJson(Map.of(
                "addon_code", addonCode,
                "stripe_session_id", stripeSessionId,
                "stripe_payment_intent_id", paymentIntentId == null ? "" : paymentIntentId
        )));
        ledger.setCreatedAt(LocalDateTime.now());
        quotaLedgerMapper.insert(ledger);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pauseAll(String clerkUserId, String subscriptionId, String idempotencyKey) {
        if (hasLedger(LEDGER_TYPE_ADDON_PAUSE, idempotencyKey)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<UserAddonGrantEntity> grants = userAddonGrantMapper.selectList(
                new LambdaQueryWrapper<UserAddonGrantEntity>()
                        .eq(UserAddonGrantEntity::getClerkUserId, clerkUserId)
                        .eq(UserAddonGrantEntity::getGrantType, "addon")
                        .eq(UserAddonGrantEntity::getStatus, "active")
                        .gt(UserAddonGrantEntity::getRemainingAmount, 0));
        for (UserAddonGrantEntity grant : grants) {
            if (grant.getExpiresAt() != null && !grant.getExpiresAt().isAfter(now)) {
                grant.setStatus("expired");
            } else {
                grant.setStatus("paused");
                grant.setPausedAt(now);
            }
            grant.setUpdatedAt(now);
            updateGrantOrThrow(grant, "pause add-on grants");
        }
        insertLifecycleLedger(clerkUserId, subscriptionId, idempotencyKey, LEDGER_TYPE_ADDON_PAUSE, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resumeEligible(String clerkUserId, String subscriptionId, String idempotencyKey) {
        if (hasLedger(LEDGER_TYPE_ADDON_RESUME, idempotencyKey)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<UserAddonGrantEntity> grants = userAddonGrantMapper.selectList(
                new LambdaQueryWrapper<UserAddonGrantEntity>()
                        .eq(UserAddonGrantEntity::getClerkUserId, clerkUserId)
                        .eq(UserAddonGrantEntity::getGrantType, "addon")
                        .eq(UserAddonGrantEntity::getStatus, "paused")
                        .gt(UserAddonGrantEntity::getRemainingAmount, 0));
        for (UserAddonGrantEntity grant : grants) {
            if (grant.getExpiresAt() != null && !grant.getExpiresAt().isAfter(now)) {
                grant.setStatus("expired");
            } else {
                grant.setStatus("active");
                grant.setPausedAt(null);
            }
            grant.setUpdatedAt(now);
            resumeGrantOrThrow(grant, now);
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
                                .in(UserAddonGrantEntity::getGrantType, List.of("addon", "compensation", "legacy"))
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
        return "QL" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) +
                UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}
