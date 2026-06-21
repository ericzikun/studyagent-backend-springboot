package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.mapper.*;
import com.studyagent.service.domain.quota.ConsumeResult;
import com.studyagent.service.domain.quota.PlanQuotaService;
import com.studyagent.service.domain.quota.QuotaBalance;
import com.studyagent.service.domain.quota.QuotaDomainService;
import com.studyagent.service.domain.quota.QuotaLedgerItem;
import com.studyagent.service.domain.quota.QuotaLedgerPageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 额度领域服务实现
 * 负责额度查询、消费扣减、失败回滚
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaDomainServiceImpl implements QuotaDomainService {
    private static final Gson GSON = new Gson();

    private final AiFeatureDefsMapper aiFeatureDefsMapper;
    private final AiFeaturePackageMapper aiFeaturePackageMapper;
    private final UserAiQuotaMapper userAiQuotaMapper;
    private final QuotaLedgerMapper quotaLedgerMapper;
    private final UserAddonGrantMapper userAddonGrantMapper;
    private final QuotaLedgerAllocationMapper quotaLedgerAllocationMapper;
    private final PlanQuotaService planQuotaService;

    private static final String LEDGER_TYPE_CONSUME = "consume";
    private static final String LEDGER_TYPE_REFUND = "refund";
    private static final String LEDGER_TYPE_RECHARGE = "recharge";
    private static final String LEDGER_TYPE_FREE_REFRESH = "free_refresh";
    private static final String POOL_TYPE_FREE = "free";
    private static final String POOL_TYPE_PLAN = "plan";
    private static final String POOL_TYPE_ADDON = "addon";
    private static final String POOL_TYPE_LEGACY = "legacy";
    private static final String POOL_TYPE_COMPENSATION = "compensation";
    private static final String SOURCE_TYPE_SYSTEM = "system";
    private static final String LEDGER_TYPE_COMPENSATION_GRANT = "compensation_grant";
    private static final String PERIOD_MONTHLY = "monthly";
    private static final String PERIOD_WEEKLY = "weekly";
    private static final String PERIOD_DAILY = "daily";
    private static final long LEGACY_WORDS_PER_RUN = 10_000L;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuotaBalance getUserQuota(String clerkUserId, String featureCode) {
        // 1. 获取功能定义
        AiFeatureDefsEntity featureDef = aiFeatureDefsMapper.selectOne(
                new LambdaQueryWrapper<AiFeatureDefsEntity>()
                        .eq(AiFeatureDefsEntity::getFeatureCode, featureCode)
                        .eq(AiFeatureDefsEntity::getIsActive, true)
                        .last("LIMIT 1"));
        if (featureDef == null) {
            throw new IllegalArgumentException("Unknown feature_code: " + featureCode);
        }

        planQuotaService.refreshPlanQuotaIfNeeded(clerkUserId, featureCode);

        long freeQuotaAmount = featureDef.getFreeQuotaAmount() != null ? featureDef.getFreeQuotaAmount() : 0L;

        // 2. 获取或初始化用户额度
        UserAiQuotaEntity quota = userAiQuotaMapper.selectOne(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, clerkUserId)
                        .eq(UserAiQuotaEntity::getFeatureCode, featureCode)
                        .last("LIMIT 1"));

        if (quota == null) {
            // 新用户：返回免费额度（尚未持久化，consume 时会创建）
            long freeBalance = freeQuotaAmount;
            long nonFreeBalance = 0L;
            LocalDateTime periodEnd = computePeriodEnd(LocalDateTime.now(), featureDef.getFreeQuotaPeriod());
            return new QuotaBalance(
                    featureCode,
                    featureDef.getFeatureName(),
                    featureDef.getQuotaUnit(),
                    freeBalance,
                    freeQuotaAmount,
                    periodEnd,
                    0L,
                    null,
                    0L,
                    List.of(),
                    0L,
                    freeBalance + nonFreeBalance
            );
        }

        // 3. 懒刷新：周期过期或功能周期配置变更时持久化，并写 free_refresh 流水
        LocalDateTime now = LocalDateTime.now();
        refreshFreeQuotaIfNeeded(quota, featureDef, now, "balance_query");

        long freeBalance = quota.getFreeBalance() != null ? quota.getFreeBalance() : 0L;
        long planBalance = quota.getPlanBalance() != null ? quota.getPlanBalance() : 0L;
        long legacyRawBalance = quota.getPaidBalance() != null ? quota.getPaidBalance() : 0L;
        long legacyBalance = legacyBalanceInRuns(featureCode, legacyRawBalance);
        List<UserAddonGrantEntity> addonGrants = findAddonGrantsForBalance(clerkUserId, featureCode);
        long addonBalance = addonGrants.stream()
                .filter(this::isGrantConsumable)
                .map(UserAddonGrantEntity::getRemainingAmount)
                .filter(value -> value != null && value > 0)
                .mapToLong(Long::longValue)
                .sum();
        LocalDateTime periodEnd = quota.getFreePeriodEnd();
        long nonFreeBalance = planBalance + addonBalance + legacyBalance;

        return new QuotaBalance(
                featureCode,
                featureDef.getFeatureName(),
                featureDef.getQuotaUnit(),
                freeBalance,
                freeQuotaAmount,
                periodEnd,
                planBalance,
                quota.getPlanPeriodEnd(),
                addonBalance,
                addonGrants.stream().map(this::toAddonBalanceItem).collect(Collectors.toList()),
                legacyBalance,
                freeBalance + nonFreeBalance
        );
    }

    @Override
    public boolean canConsume(String clerkUserId, String featureCode, long amount) {
        QuotaBalance quota = getUserQuota(clerkUserId, featureCode);
        return quota.totalAvailable() >= amount;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<QuotaBalance> getAllUserQuotas(String clerkUserId) {
        List<AiFeatureDefsEntity> featureDefs = aiFeatureDefsMapper.selectList(
                new LambdaQueryWrapper<AiFeatureDefsEntity>()
                        .eq(AiFeatureDefsEntity::getIsActive, true)
                        .orderByAsc(AiFeatureDefsEntity::getDisplayOrder));
        return featureDefs.stream()
                .map(def -> getUserQuota(clerkUserId, def.getFeatureCode()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsumeResult consume(
            String clerkUserId,
            String featureCode,
            long amount,
            String sourceType,
            String sourceId,
            Map<String, Object> bizContext) {
        return consume(clerkUserId, featureCode, amount, sourceType, sourceId, bizContext, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsumeResult consume(
            String clerkUserId,
            String featureCode,
            long amount,
            String sourceType,
            String sourceId,
            Map<String, Object> bizContext,
            String idempotencyKey) {

        if (amount <= 0) {
            throw new IllegalArgumentException("consume amount must be positive");
        }

        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        if (normalizedIdempotencyKey != null) {
            QuotaLedgerEntity existingLedger = quotaLedgerMapper.selectOne(
                    new LambdaQueryWrapper<QuotaLedgerEntity>()
                            .eq(QuotaLedgerEntity::getClerkUserId, clerkUserId)
                            .eq(QuotaLedgerEntity::getFeatureCode, featureCode)
                            .eq(QuotaLedgerEntity::getLedgerType, LEDGER_TYPE_CONSUME)
                            .eq(QuotaLedgerEntity::getIdempotencyKey, normalizedIdempotencyKey)
                            .last("LIMIT 1"));
            if (existingLedger != null && existingLedger.getId() != null) {
                log.info("额度消费幂等命中: clerk_user_id={}, feature={}, key={}, ledger_id={}",
                        clerkUserId, featureCode, normalizedIdempotencyKey, existingLedger.getId());
                return new ConsumeResult(existingLedger.getId());
            }
        }

        // 1. 获取功能定义和用户额度（需在事务内刷新周期）
        AiFeatureDefsEntity featureDef = aiFeatureDefsMapper.selectOne(
                new LambdaQueryWrapper<AiFeatureDefsEntity>()
                        .eq(AiFeatureDefsEntity::getFeatureCode, featureCode)
                        .eq(AiFeatureDefsEntity::getIsActive, true)
                        .last("LIMIT 1"));
        if (featureDef == null) {
            throw new IllegalArgumentException("Unknown feature_code: " + featureCode);
        }

        planQuotaService.refreshPlanQuotaIfNeeded(clerkUserId, featureCode);

        UserAiQuotaEntity quota = userAiQuotaMapper.selectOne(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, clerkUserId)
                        .eq(UserAiQuotaEntity::getFeatureCode, featureCode)
                        .last("LIMIT 1"));

        LocalDateTime now = LocalDateTime.now();
        long freeQuotaAmount = featureDef.getFreeQuotaAmount() != null ? featureDef.getFreeQuotaAmount() : 0L;

        // 2. 不存在则创建，存在则检查并刷新周期
        if (quota == null) {
            quota = new UserAiQuotaEntity();
            quota.setClerkUserId(clerkUserId);
            quota.setFeatureCode(featureCode);
            quota.setFreeBalance(freeQuotaAmount);
            quota.setPlanBalance(0L);
            quota.setPaidBalance(0L);
            quota.setVersion(0);
            LocalDateTime periodStart = now;
            LocalDateTime periodEnd = computePeriodEnd(periodStart, featureDef.getFreeQuotaPeriod());
            quota.setFreePeriodStart(periodStart);
            quota.setFreePeriodEnd(periodEnd);
            quota.setCreatedAt(now);
            quota.setUpdatedAt(now);
            userAiQuotaMapper.insert(quota);
        } else {
            refreshFreeQuotaIfNeeded(quota, featureDef, now, "consume");
        }

        long freeBalance = quota.getFreeBalance() != null ? quota.getFreeBalance() : 0L;
        long planBalance = quota.getPlanBalance() != null ? quota.getPlanBalance() : 0L;
        long legacyRawBalance = quota.getPaidBalance() != null ? quota.getPaidBalance() : 0L;
        long legacyBalance = legacyBalanceInRuns(featureCode, legacyRawBalance);
        List<UserAddonGrantEntity> activeAddonGrants = findActiveAddonGrants(clerkUserId, featureCode, now);
        long addonBalance = activeAddonGrants.stream()
                .map(UserAddonGrantEntity::getRemainingAmount)
                .filter(value -> value != null && value > 0)
                .mapToLong(Long::longValue)
                .sum();
        long totalAvailable = freeBalance + planBalance + addonBalance + legacyBalance;

        if (totalAvailable < amount) {
            throw new IllegalStateException(
                    String.format("Insufficient quota: need %d, available %d (free=%d, plan=%d, addon=%d, legacy=%d)",
                            amount, totalAvailable, freeBalance, planBalance, addonBalance, legacyBalance));
        }

        // 3. 扣减：free -> plan -> addon -> legacy
        long remaining = amount;
        long fromFree = Math.min(freeBalance, remaining);
        remaining -= fromFree;
        long newFreeBalance = freeBalance - fromFree;

        long fromPlan = Math.min(planBalance, remaining);
        remaining -= fromPlan;
        long newPlanBalance = planBalance - fromPlan;

        List<QuotaLedgerAllocationEntity> allocations = new ArrayList<>();
        if (fromFree > 0) {
            allocations.add(newAllocation(POOL_TYPE_FREE, null, fromFree, now, quota.getFreePeriodEnd()));
        }
        if (fromPlan > 0) {
            allocations.add(newAllocation(POOL_TYPE_PLAN, null, fromPlan, now, quota.getPlanPeriodEnd()));
        }

        long addonConsumed = 0L;
        for (UserAddonGrantEntity grant : activeAddonGrants) {
            if (remaining <= 0) {
                break;
            }
            long grantRemaining = grant.getRemainingAmount() != null ? grant.getRemainingAmount() : 0L;
            if (grantRemaining <= 0) {
                continue;
            }
            long consumeFromGrant = Math.min(grantRemaining, remaining);
            remaining -= consumeFromGrant;
            addonConsumed += consumeFromGrant;
            grant.setRemainingAmount(grantRemaining - consumeFromGrant);
            if (grant.getRemainingAmount() <= 0) {
                grant.setRemainingAmount(0L);
                grant.setStatus("depleted");
            }
            updateAddonGrantOrThrow(grant, "consume add-on grant");
            allocations.add(newAllocation(POOL_TYPE_ADDON, grant.getId(), consumeFromGrant, now, grant.getExpiresAt()));
        }

        long fromLegacy = remaining;
        long legacyRawDebit = legacyRawDebitForRuns(featureCode, legacyRawBalance, fromLegacy);
        long newLegacyRawBalance = legacyRawBalance - legacyRawDebit;
        long newLegacyBalance = legacyBalanceInRuns(featureCode, newLegacyRawBalance);
        if (fromLegacy > 0) {
            allocations.add(newAllocation(POOL_TYPE_LEGACY, null, legacyRawDebit, now, null));
        }
        if (newLegacyRawBalance < 0) {
            throw new IllegalStateException("Legacy balance became negative after allocation");
        }

        long newAddonBalance = addonBalance - addonConsumed;

        // 4. 更新 user_ai_quotas
        quota.setFreeBalance(newFreeBalance);
        quota.setPlanBalance(newPlanBalance);
        quota.setPaidBalance(newLegacyRawBalance);
        quota.setUpdatedAt(now);
        updateQuotaOrThrow(quota, "consume");

        // 5. 写入消费流水（amount 为负数表示扣减）
        Map<String, Object> ctx = bizContext != null ? new HashMap<>(bizContext) : new HashMap<>();
        ctx.put("consumed", amount);
        ctx.put("from_free_amount", fromFree);
        ctx.put("from_plan_amount", fromPlan);
        ctx.put("from_addon_amount", addonConsumed);
        ctx.put("from_paid_amount", fromLegacy);
        if (isLegacyWordsFeature(featureCode) && fromLegacy > 0) {
            ctx.put("from_paid_raw_words", legacyRawDebit);
            ctx.put("legacy_words_per_run", LEGACY_WORDS_PER_RUN);
        }

        QuotaLedgerEntity ledger = new QuotaLedgerEntity();
        ledger.setLedgerNo(generateLedgerNo());
        ledger.setClerkUserId(clerkUserId);
        ledger.setFeatureCode(featureCode);
        ledger.setLedgerType(LEDGER_TYPE_CONSUME);
        ledger.setAmount(-amount);
        ledger.setSourceType(sourceType);
        ledger.setSourceId(sourceId);
        ledger.setIdempotencyKey(normalizedIdempotencyKey);
        ledger.setFreeBalanceAfter(newFreeBalance);
        ledger.setPlanBalanceAfter(newPlanBalance);
        ledger.setAddonBalanceAfter(newAddonBalance);
        ledger.setPaidBalanceAfter(newLegacyBalance);
        ledger.setBizContext(GSON.toJson(ctx));
        ledger.setCreatedAt(now);
        quotaLedgerMapper.insert(ledger);

        for (QuotaLedgerAllocationEntity allocation : allocations) {
            allocation.setQuotaLedgerId(ledger.getId());
            quotaLedgerAllocationMapper.insert(allocation);
        }

        log.info("额度消费: clerk_user_id={}, feature={}, amount={}, free={}, plan={}, addon={}, legacy={}, legacyRawDebit={}, ledger_id={}",
                clerkUserId, featureCode, amount, fromFree, fromPlan, addonConsumed, fromLegacy, legacyRawDebit, ledger.getId());
        return new ConsumeResult(ledger.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refund(long ledgerId, String reason) {
        QuotaLedgerEntity consumeLedger = quotaLedgerMapper.selectById(ledgerId);
        if (consumeLedger == null) {
            throw new IllegalArgumentException("Ledger not found: " + ledgerId);
        }
        if (!LEDGER_TYPE_CONSUME.equals(consumeLedger.getLedgerType())) {
            throw new IllegalArgumentException("Only consume ledger can be refunded: " + ledgerId);
        }

        String refundIdempotencyKey = "refund:" + ledgerId;
        QuotaLedgerEntity refundExist = quotaLedgerMapper.selectOne(
                new LambdaQueryWrapper<QuotaLedgerEntity>()
                        .eq(QuotaLedgerEntity::getLedgerType, LEDGER_TYPE_REFUND)
                        .eq(QuotaLedgerEntity::getIdempotencyKey, refundIdempotencyKey)
                        .last("LIMIT 1"));
        if (refundExist != null) {
            log.info("额度已退过，幂等跳过: original_ledger_id={}, refund_ledger_id={}",
                    ledgerId, refundExist.getId());
            return;
        }

        // amount 为负数，回滚时加回
        long refundAmount = Math.abs(consumeLedger.getAmount());
        if (refundAmount <= 0) {
            return;
        }

        UserAiQuotaEntity quota = userAiQuotaMapper.selectOne(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, consumeLedger.getClerkUserId())
                        .eq(UserAiQuotaEntity::getFeatureCode, consumeLedger.getFeatureCode())
                        .last("LIMIT 1"));
        if (quota == null) {
            throw new IllegalStateException("User quota not found for refund: " + consumeLedger.getClerkUserId());
        }

        LocalDateTime now = LocalDateTime.now();
        List<QuotaLedgerAllocationEntity> allocations = quotaLedgerAllocationMapper.selectList(
                new LambdaQueryWrapper<QuotaLedgerAllocationEntity>()
                        .eq(QuotaLedgerAllocationEntity::getQuotaLedgerId, ledgerId)
                        .orderByAsc(QuotaLedgerAllocationEntity::getId));

        long addToFree = 0L;
        long addToPlan = 0L;
        long addToPaid = 0L;
        List<QuotaLedgerAllocationEntity> refundAllocations = new ArrayList<>();
        if (allocations == null || allocations.isEmpty()) {
            JsonObject obj = parseBizContext(consumeLedger.getBizContext());
            addToFree = obj != null && obj.has("from_free_amount") ? obj.get("from_free_amount").getAsLong() : 0L;
            addToPlan = obj != null && obj.has("from_plan_amount") ? obj.get("from_plan_amount").getAsLong() : 0L;
            addToPaid = obj != null && obj.has("from_paid_amount")
                    ? obj.get("from_paid_amount").getAsLong()
                    : Math.max(0L, refundAmount - addToFree - addToPlan);
            if (addToFree > 0) {
                refundAllocations.add(newAllocation(POOL_TYPE_FREE, null, addToFree, now, quota.getFreePeriodEnd()));
            }
            if (addToPlan > 0) {
                refundAllocations.add(newAllocation(POOL_TYPE_PLAN, null, addToPlan, now, quota.getPlanPeriodEnd()));
            }
            if (addToPaid > 0) {
                refundAllocations.add(newAllocation(POOL_TYPE_LEGACY, null, addToPaid, now, null));
            }
        } else {
            for (QuotaLedgerAllocationEntity allocation : allocations) {
                if (allocation == null || allocation.getAmount() == null || allocation.getAmount() <= 0) {
                    continue;
                }
                long allocationAmount = allocation.getAmount();
                switch (allocation.getPoolType()) {
                    case POOL_TYPE_FREE -> {
                        if (isAllocationExpired(allocation, now)) {
                            refundAllocations.add(createCompensationGrant(
                                    consumeLedger.getClerkUserId(), consumeLedger.getFeatureCode(), allocationAmount, now));
                        } else {
                            addToFree += allocationAmount;
                            refundAllocations.add(newAllocation(
                                    POOL_TYPE_FREE, null, allocationAmount, now, allocation.getSourcePeriodEnd()));
                        }
                    }
                    case POOL_TYPE_PLAN -> {
                        if (isAllocationExpired(allocation, now)) {
                            refundAllocations.add(createCompensationGrant(
                                    consumeLedger.getClerkUserId(), consumeLedger.getFeatureCode(), allocationAmount, now));
                        } else {
                            addToPlan += allocationAmount;
                            refundAllocations.add(newAllocation(
                                    POOL_TYPE_PLAN, null, allocationAmount, now, allocation.getSourcePeriodEnd()));
                        }
                    }
                    case POOL_TYPE_LEGACY -> {
                        addToPaid += allocationAmount;
                        refundAllocations.add(newAllocation(
                                POOL_TYPE_LEGACY, null, allocationAmount, now, allocation.getSourcePeriodEnd()));
                    }
                    case POOL_TYPE_ADDON, POOL_TYPE_COMPENSATION -> refundAllocations.add(
                            restoreAddonGrantOrCompensate(
                                    consumeLedger.getClerkUserId(),
                                    consumeLedger.getFeatureCode(),
                                    allocation,
                                    now));
                    default -> log.warn("Unknown allocation pool type on refund: ledgerId={}, poolType={}",
                            ledgerId, allocation.getPoolType());
                }
            }
        }

        long newFree = (quota.getFreeBalance() != null ? quota.getFreeBalance() : 0L) + addToFree;
        long newPlan = (quota.getPlanBalance() != null ? quota.getPlanBalance() : 0L) + addToPlan;
        long newPaid = (quota.getPaidBalance() != null ? quota.getPaidBalance() : 0L) + addToPaid;
        long displayPaidAfter = legacyBalanceInRuns(consumeLedger.getFeatureCode(), newPaid);
        quota.setFreeBalance(newFree);
        quota.setPlanBalance(newPlan);
        quota.setPaidBalance(newPaid);
        quota.setUpdatedAt(now);
        updateQuotaOrThrow(quota, "refund");

        // 写入回滚流水
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("refund_reason", reason);
        ctx.put("original_ledger_id", ledgerId);
        ctx.put("refunded", refundAmount);

        QuotaLedgerEntity refundLedger = new QuotaLedgerEntity();
        refundLedger.setLedgerNo(generateLedgerNo());
        refundLedger.setClerkUserId(consumeLedger.getClerkUserId());
        refundLedger.setFeatureCode(consumeLedger.getFeatureCode());
        refundLedger.setLedgerType(LEDGER_TYPE_REFUND);
        refundLedger.setAmount(refundAmount);
        refundLedger.setSourceType(consumeLedger.getSourceType());
        refundLedger.setSourceId(consumeLedger.getSourceId());
        refundLedger.setIdempotencyKey(refundIdempotencyKey);
        refundLedger.setSubscriptionId(consumeLedger.getSubscriptionId());
        refundLedger.setInvoiceId(consumeLedger.getInvoiceId());
        refundLedger.setFreeBalanceAfter(newFree);
        refundLedger.setPlanBalanceAfter(newPlan);
        refundLedger.setAddonBalanceAfter(sumActiveAddonBalance(
                consumeLedger.getClerkUserId(), consumeLedger.getFeatureCode(), now));
        refundLedger.setPaidBalanceAfter(displayPaidAfter);
        refundLedger.setBizContext(GSON.toJson(ctx));
        refundLedger.setCreatedAt(now);
        quotaLedgerMapper.insert(refundLedger);
        for (QuotaLedgerAllocationEntity allocation : refundAllocations) {
            allocation.setQuotaLedgerId(refundLedger.getId());
            quotaLedgerAllocationMapper.insert(allocation);
        }

        log.info("额度回滚: ledger_id={}, clerk_user_id={}, feature={}, amount={}, reason={}",
                ledgerId, consumeLedger.getClerkUserId(), consumeLedger.getFeatureCode(), refundAmount, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundByTaskId(long taskId, String reason) {
        QuotaLedgerEntity consumeLedger = quotaLedgerMapper.selectOne(
                new LambdaQueryWrapper<QuotaLedgerEntity>()
                        .eq(QuotaLedgerEntity::getSourceType, "task")
                        .eq(QuotaLedgerEntity::getSourceId, String.valueOf(taskId))
                        .eq(QuotaLedgerEntity::getLedgerType, LEDGER_TYPE_CONSUME)
                        .last("LIMIT 1"));
        if (consumeLedger == null) {
            log.warn("任务 {} 未找到消费流水，跳过回滚", taskId);
            return;
        }
        refund(consumeLedger.getId(), reason);
    }

    @Override
    @Transactional(readOnly = true)
    public QuotaLedgerPageResult getLedgerPage(String clerkUserId, String featureCode, int page, int pageSize) {
        LambdaQueryWrapper<QuotaLedgerEntity> wrapper = new LambdaQueryWrapper<QuotaLedgerEntity>()
                .eq(QuotaLedgerEntity::getClerkUserId, clerkUserId)
                .orderByDesc(QuotaLedgerEntity::getCreatedAt);
        if (featureCode != null && !featureCode.isEmpty()) {
            wrapper.eq(QuotaLedgerEntity::getFeatureCode, featureCode);
        }

        Page<QuotaLedgerEntity> pageParam = new Page<>(page, pageSize);
        IPage<QuotaLedgerEntity> pageResult = quotaLedgerMapper.selectPage(pageParam, wrapper);
        List<QuotaLedgerEntity> entities = pageResult.getRecords();

        List<QuotaLedgerItem> items = entities.stream()
                .map(this::toLedgerItem)
                .collect(Collectors.toList());

        return new QuotaLedgerPageResult(items, pageResult.getTotal());
    }

    private QuotaLedgerItem toLedgerItem(QuotaLedgerEntity entity) {
        String featureDisplayName = resolveFeatureDisplayName(entity.getFeatureCode());
        String quotaUnit = "time";
        AiFeatureDefsEntity featureDef = aiFeatureDefsMapper.selectOne(
                new LambdaQueryWrapper<AiFeatureDefsEntity>()
                        .eq(AiFeatureDefsEntity::getFeatureCode, entity.getFeatureCode())
                        .last("LIMIT 1"));
        if (featureDef != null) {
            quotaUnit = normalizeQuotaUnit(featureDef.getQuotaUnit());
        }

        String displayText = buildDisplayText(entity, featureDisplayName, quotaUnit);
        return new QuotaLedgerItem(
                entity.getId(),
                entity.getLedgerNo(),
                entity.getLedgerType(),
                entity.getAmount(),
                entity.getSourceType(),
                entity.getSourceId(),
                displayText,
                entity.getFreeBalanceAfter(),
                entity.getPlanBalanceAfter(),
                entity.getAddonBalanceAfter(),
                entity.getPaidBalanceAfter(),
                entity.getCreatedAt(),
                entity.getFeatureCode(),
                quotaUnit,
                buildLedgerAllocations(entity.getId())
        );
    }

    /**
     * 将 ai_feature_defs 的 quota_unit 规范化为前端 CreditUnit：time | words
     */
    private String normalizeQuotaUnit(String rawUnit) {
        if (rawUnit == null || rawUnit.isEmpty()) {
            return "time";
        }
        return "count".equalsIgnoreCase(rawUnit) ? "time" : "words";
    }

    private long legacyBalanceInRuns(String featureCode, long rawPaidBalance) {
        if (rawPaidBalance <= 0) {
            return 0L;
        }
        if (!isLegacyWordsFeature(featureCode)) {
            return rawPaidBalance;
        }
        return (rawPaidBalance + LEGACY_WORDS_PER_RUN - 1) / LEGACY_WORDS_PER_RUN;
    }

    private long legacyRawDebitForRuns(String featureCode, long rawPaidBalance, long runAmount) {
        if (runAmount <= 0 || rawPaidBalance <= 0) {
            return 0L;
        }
        if (!isLegacyWordsFeature(featureCode)) {
            return runAmount;
        }
        return Math.min(rawPaidBalance, runAmount * LEGACY_WORDS_PER_RUN);
    }

    private boolean isLegacyWordsFeature(String featureCode) {
        return false;
    }

    private String buildDisplayText(QuotaLedgerEntity entity, String featureDisplayName, String quotaUnit) {
        long absAmount = Math.abs(entity.getAmount());
        String unitStr = "words".equals(quotaUnit) ? "words" : (absAmount == 1 ? "time" : "times");
        JsonObject biz = parseBizContext(entity.getBizContext());

        return switch (entity.getLedgerType()) {
            case LEDGER_TYPE_CONSUME -> switch (entity.getSourceType()) {
                case "task" -> featureDisplayName + " consumed " + absAmount + " " + unitStr;
                // V2 verla 链路扣费：与 V1 task 文案一致，前端无需新增分支
                case "verla_session" -> featureDisplayName + " consumed " + absAmount + " " + unitStr;
                default -> "Consumed " + absAmount + " " + unitStr;
            };
            case LEDGER_TYPE_REFUND -> switch (entity.getSourceType()) {
                case "task" -> "Task failed, refunded " + absAmount + " " + unitStr;
                case "verla_session" -> "Task failed, refunded " + absAmount + " " + unitStr;
                default -> "Refunded " + absAmount + " " + unitStr;
            };
            case LEDGER_TYPE_RECHARGE -> {
                String packageCode = biz != null && biz.has("package_code") ? biz.get("package_code").getAsString() : null;
                String packageName = resolvePackageName(entity.getFeatureCode(), packageCode);
                long quotaAmount = biz != null && biz.has("quota_amount") ? biz.get("quota_amount").getAsLong() : absAmount;
                int priceCents = biz != null && biz.has("price_cents") ? biz.get("price_cents").getAsInt() : 0;
                String priceStr = priceCents > 0 ? String.format(" ($%.2f)", priceCents / 100.0) : "";
                String qUnit = "words".equals(quotaUnit) ? "words" : (quotaAmount == 1 ? "time" : "times");
                yield "Recharged " + packageName + ", +" + quotaAmount + " " + qUnit + priceStr;
            }
            case LEDGER_TYPE_FREE_REFRESH -> {
                String period = biz != null && biz.has("free_quota_period")
                        ? biz.get("free_quota_period").getAsString() : "period";
                yield featureDisplayName + " free quota refreshed (+" + absAmount + " " + unitStr + ", " + period + ")";
            }
            default -> entity.getLedgerType() + " " + absAmount + " " + unitStr;
        };
    }

    /**
     * 免费额度懒刷新：周期到期，或 ai_feature_defs 周期配置与当前用户窗口不一致时重置。
     */
    private void refreshFreeQuotaIfNeeded(
            UserAiQuotaEntity quota,
            AiFeatureDefsEntity featureDef,
            LocalDateTime now,
            String trigger) {
        if (quota == null || quota.getId() == null || featureDef == null) {
            return;
        }
        long freeQuotaAmount = featureDef.getFreeQuotaAmount() != null ? featureDef.getFreeQuotaAmount() : 0L;
        String configuredPeriod = normalizePeriod(featureDef.getFreeQuotaPeriod());
        boolean periodExpired = quota.getFreePeriodEnd() != null && now.isAfter(quota.getFreePeriodEnd());
        boolean periodConfigChanged = quota.getFreePeriodStart() != null
                && quota.getFreePeriodEnd() != null
                && !configuredPeriod.equals(inferStoredPeriod(quota.getFreePeriodStart(), quota.getFreePeriodEnd()));
        if (!periodExpired && !periodConfigChanged) {
            return;
        }

        long oldFreeBalance = quota.getFreeBalance() != null ? quota.getFreeBalance() : 0L;
        long delta = Math.max(0, freeQuotaAmount - oldFreeBalance);
        LocalDateTime periodStart = now;
        LocalDateTime periodEnd = computePeriodEnd(periodStart, configuredPeriod);

        quota.setFreeBalance(freeQuotaAmount);
        quota.setFreePeriodStart(periodStart);
        quota.setFreePeriodEnd(periodEnd);
        quota.setUpdatedAt(now);
        updateQuotaOrThrow(quota, "free_refresh");

        if (delta <= 0) {
            return;
        }

        long paidBalance = quota.getPaidBalance() != null ? quota.getPaidBalance() : 0L;
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("free_quota_period", configuredPeriod);
        ctx.put("refresh_trigger", trigger);
        ctx.put("previous_free_balance", oldFreeBalance);
        ctx.put("refreshed_to", freeQuotaAmount);
        if (periodConfigChanged) {
            ctx.put("reason", "period_config_changed");
        } else {
            ctx.put("reason", "period_expired");
        }

        QuotaLedgerEntity ledger = new QuotaLedgerEntity();
        ledger.setLedgerNo(generateLedgerNo());
        ledger.setClerkUserId(quota.getClerkUserId());
        ledger.setFeatureCode(quota.getFeatureCode());
        ledger.setLedgerType(LEDGER_TYPE_FREE_REFRESH);
        ledger.setAmount(delta);
        ledger.setSourceType(SOURCE_TYPE_SYSTEM);
        ledger.setSourceId(quota.getFeatureCode());
        ledger.setFreeBalanceAfter(freeQuotaAmount);
        ledger.setPaidBalanceAfter(paidBalance);
        ledger.setBizContext(new com.google.gson.Gson().toJson(ctx));
        ledger.setCreatedAt(now);
        quotaLedgerMapper.insert(ledger);

        log.info("免费额度刷新: user={}, feature={}, delta={}, period={}, trigger={}",
                quota.getClerkUserId(), quota.getFeatureCode(), delta, configuredPeriod, trigger);
    }

    private static String normalizePeriod(String period) {
        if (period == null || period.isBlank()) {
            return PERIOD_MONTHLY;
        }
        return period.trim().toLowerCase();
    }

    private String inferStoredPeriod(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            return PERIOD_MONTHLY;
        }
        long hours = java.time.Duration.between(start, end).toHours();
        if (hours <= 36) {
            return PERIOD_DAILY;
        }
        if (hours <= 24 * 8) {
            return PERIOD_WEEKLY;
        }
        return PERIOD_MONTHLY;
    }

    private String resolveFeatureDisplayName(String featureCode) {
        if (featureCode == null) {
            return "Quota";
        }
        return switch (featureCode) {
            case "task_create" -> "Assignment";
            case "ai_detection" -> "AI Detection";
            case "humanizer" -> "Humanizer";
            default -> featureCode;
        };
    }

    private String resolvePackageName(String featureCode, String packageCode) {
        if (packageCode == null || packageCode.isEmpty()) {
            return "Package";
        }
        AiFeaturePackageEntity pkg = aiFeaturePackageMapper.selectOne(
                new LambdaQueryWrapper<AiFeaturePackageEntity>()
                        .eq(AiFeaturePackageEntity::getFeatureCode, featureCode)
                        .eq(AiFeaturePackageEntity::getPackageCode, packageCode)
                        .last("LIMIT 1"));
        return pkg != null && pkg.getPackageName() != null ? pkg.getPackageName() : packageCode;
    }

    private JsonObject parseBizContext(String bizContext) {
        if (bizContext == null || bizContext.isEmpty()) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(bizContext);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        String normalized = idempotencyKey.trim();
        return normalized.isEmpty() ? null : normalized;
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

    private List<UserAddonGrantEntity> findActiveAddonGrants(String clerkUserId, String featureCode, LocalDateTime now) {
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
                .gt(UserAddonGrantEntity::getRemainingAmount, 0)
                                .orderByAsc(UserAddonGrantEntity::getExpiresAt)
                                .orderByAsc(UserAddonGrantEntity::getId))
                .stream()
                .sorted(Comparator.comparing(
                                UserAddonGrantEntity::getExpiresAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(UserAddonGrantEntity::getId))
                .collect(Collectors.toList());
    }

    private List<UserAddonGrantEntity> findAddonGrantsForBalance(String clerkUserId, String featureCode) {
        return userAddonGrantMapper.selectList(
                        new LambdaQueryWrapper<UserAddonGrantEntity>()
                                .eq(UserAddonGrantEntity::getClerkUserId, clerkUserId)
                                .eq(UserAddonGrantEntity::getFeatureCode, featureCode)
                                .orderByAsc(UserAddonGrantEntity::getExpiresAt)
                                .orderByAsc(UserAddonGrantEntity::getId))
                .stream()
                .sorted(Comparator.comparing(
                                UserAddonGrantEntity::getExpiresAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(UserAddonGrantEntity::getId))
                .collect(Collectors.toList());
    }

    private long sumActiveAddonBalance(String clerkUserId, String featureCode, LocalDateTime now) {
        return findActiveAddonGrants(clerkUserId, featureCode, now).stream()
                .map(UserAddonGrantEntity::getRemainingAmount)
                .filter(value -> value != null && value > 0)
                .mapToLong(Long::longValue)
                .sum();
    }

    private boolean isAllocationExpired(QuotaLedgerAllocationEntity allocation, LocalDateTime now) {
        return allocation.getSourcePeriodEnd() != null && !allocation.getSourcePeriodEnd().isAfter(now);
    }

    private boolean isGrantConsumable(UserAddonGrantEntity grant) {
        if (grant == null) {
            return false;
        }
        if (!"active".equals(grant.getStatus())) {
            return false;
        }
        if (grant.getRemainingAmount() == null || grant.getRemainingAmount() <= 0) {
            return false;
        }
        return grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private Map<String, Object> toAddonBalanceItem(UserAddonGrantEntity grant) {
        Map<String, Object> item = new HashMap<>();
        item.put("grant_id", grant.getId());
        item.put("addon_code", grant.getAddonCode());
        item.put("grant_type", grant.getGrantType());
        item.put("status", grant.getStatus());
        item.put("balance", grant.getRemainingAmount() != null ? grant.getRemainingAmount() : 0L);
        item.put("expires_at", grant.getExpiresAt() != null ? grant.getExpiresAt().toString() : null);
        return item;
    }

    private List<Map<String, Object>> buildLedgerAllocations(Long ledgerId) {
        if (ledgerId == null) {
            return List.of();
        }
        return quotaLedgerAllocationMapper.selectList(
                        new LambdaQueryWrapper<QuotaLedgerAllocationEntity>()
                                .eq(QuotaLedgerAllocationEntity::getQuotaLedgerId, ledgerId)
                                .orderByAsc(QuotaLedgerAllocationEntity::getId))
                .stream()
                .map(allocation -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("poolType", allocation.getPoolType());
                    item.put("grantId", allocation.getGrantId());
                    item.put("amount", allocation.getAmount());
                    item.put("sourcePeriodEnd",
                            allocation.getSourcePeriodEnd() != null ? allocation.getSourcePeriodEnd().toString() : null);
                    return item;
                })
                .collect(Collectors.toList());
    }

    private QuotaLedgerAllocationEntity restoreAddonGrantOrCompensate(
            String clerkUserId,
            String featureCode,
            QuotaLedgerAllocationEntity allocation,
            LocalDateTime now) {
        if (allocation.getGrantId() == null) {
            log.warn("Skip add-on refund allocation without grant id: ledgerAllocationId={}", allocation.getId());
            return createCompensationGrant(clerkUserId, featureCode, allocation.getAmount(), now);
        }
        UserAddonGrantEntity grant = userAddonGrantMapper.selectById(allocation.getGrantId());
        if (grant == null) {
            log.warn("Skip add-on refund allocation because grant missing: grantId={}", allocation.getGrantId());
            return createCompensationGrant(clerkUserId, featureCode, allocation.getAmount(), now);
        }
        if (isAllocationExpired(allocation, now)
                || (grant.getExpiresAt() != null && !grant.getExpiresAt().isAfter(now))) {
            grant.setStatus("expired");
            grant.setUpdatedAt(now);
            updateAddonGrantOrThrow(grant, "expire refunded add-on grant");
            return createCompensationGrant(clerkUserId, featureCode, allocation.getAmount(), now);
        }
        long currentRemaining = grant.getRemainingAmount() != null ? grant.getRemainingAmount() : 0L;
        grant.setRemainingAmount(currentRemaining + allocation.getAmount());
        grant.setStatus("active");
        grant.setPausedAt(null);
        grant.setUpdatedAt(now);
        updateAddonGrantOrThrow(grant, "restore add-on grant");
        return newAllocation(grant.getGrantType(), grant.getId(), allocation.getAmount(), now, grant.getExpiresAt());
    }

    private QuotaLedgerAllocationEntity createCompensationGrant(
            String clerkUserId,
            String featureCode,
            long amount,
            LocalDateTime now) {
        UserAddonGrantEntity grant = new UserAddonGrantEntity();
        grant.setClerkUserId(clerkUserId);
        grant.setFeatureCode(featureCode);
        grant.setGrantType(POOL_TYPE_COMPENSATION);
        grant.setAddonCode(null);
        grant.setStatus("active");
        grant.setInitialAmount(amount);
        grant.setRemainingAmount(amount);
        grant.setStripeSessionId(null);
        grant.setStripePaymentIntentId(null);
        grant.setSourceOrderId(null);
        grant.setMigrationKey(null);
        grant.setPurchasedAt(now);
        grant.setExpiresAt(now.plusDays(30));
        grant.setPausedAt(null);
        grant.setVersion(0);
        grant.setCreatedAt(now);
        grant.setUpdatedAt(now);
        userAddonGrantMapper.insert(grant);

        QuotaLedgerEntity ledger = new QuotaLedgerEntity();
        ledger.setLedgerNo(generateLedgerNo());
        ledger.setClerkUserId(clerkUserId);
        ledger.setFeatureCode(featureCode);
        ledger.setLedgerType(LEDGER_TYPE_COMPENSATION_GRANT);
        ledger.setAmount(amount);
        ledger.setSourceType(SOURCE_TYPE_SYSTEM);
        ledger.setSourceId(featureCode);
        ledger.setAddonBalanceAfter(sumActiveAddonBalance(clerkUserId, featureCode, now));
        ledger.setCreatedAt(now);
        quotaLedgerMapper.insert(ledger);

        return newAllocation(POOL_TYPE_COMPENSATION, grant.getId(), amount, now, grant.getExpiresAt());
    }

    private void updateQuotaOrThrow(UserAiQuotaEntity quota, String action) {
        int updated = userAiQuotaMapper.updateById(quota);
        if (updated != 1) {
            throw new IllegalStateException("Quota update conflict during " + action + ": quotaId=" + quota.getId());
        }
    }

    private void updateAddonGrantOrThrow(UserAddonGrantEntity grant, String action) {
        int updated = userAddonGrantMapper.updateById(grant);
        if (updated != 1) {
            throw new IllegalStateException("Addon grant update conflict during " + action + ": grantId=" + grant.getId());
        }
    }

    private QuotaLedgerAllocationEntity newAllocation(
            String poolType,
            Long grantId,
            long amount,
            LocalDateTime now,
            LocalDateTime sourcePeriodEnd) {
        QuotaLedgerAllocationEntity allocation = new QuotaLedgerAllocationEntity();
        allocation.setPoolType(poolType);
        allocation.setGrantId(grantId);
        allocation.setAmount(amount);
        allocation.setSourcePeriodEnd(sourcePeriodEnd);
        allocation.setCreatedAt(now);
        return allocation;
    }

    private String generateLedgerNo() {
        return "QL" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) +
                UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}
