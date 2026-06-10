package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.mapper.*;
import com.studyagent.service.domain.quota.ConsumeResult;
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

    private final AiFeatureDefsMapper aiFeatureDefsMapper;
    private final AiFeaturePackageMapper aiFeaturePackageMapper;
    private final UserAiQuotaMapper userAiQuotaMapper;
    private final QuotaLedgerMapper quotaLedgerMapper;

    private static final String LEDGER_TYPE_CONSUME = "consume";
    private static final String LEDGER_TYPE_REFUND = "refund";
    private static final String LEDGER_TYPE_RECHARGE = "recharge";
    private static final String LEDGER_TYPE_FREE_REFRESH = "free_refresh";
    private static final String SOURCE_TYPE_SYSTEM = "system";
    private static final String PERIOD_MONTHLY = "monthly";
    private static final String PERIOD_WEEKLY = "weekly";
    private static final String PERIOD_DAILY = "daily";

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
            long paidBalance = 0L;
            LocalDateTime periodEnd = computePeriodEnd(LocalDateTime.now(), featureDef.getFreeQuotaPeriod());
            return new QuotaBalance(
                    featureCode,
                    featureDef.getFeatureName(),
                    featureDef.getQuotaUnit(),
                    freeBalance,
                    freeQuotaAmount,
                    periodEnd,
                    paidBalance,
                    freeBalance + paidBalance
            );
        }

        // 3. 懒刷新：周期过期或功能周期配置变更时持久化，并写 free_refresh 流水
        LocalDateTime now = LocalDateTime.now();
        refreshFreeQuotaIfNeeded(quota, featureDef, now, "balance_query");

        long freeBalance = quota.getFreeBalance() != null ? quota.getFreeBalance() : 0L;
        long paidBalance = quota.getPaidBalance() != null ? quota.getPaidBalance() : 0L;
        LocalDateTime periodEnd = quota.getFreePeriodEnd();

        return new QuotaBalance(
                featureCode,
                featureDef.getFeatureName(),
                featureDef.getQuotaUnit(),
                freeBalance,
                freeQuotaAmount,
                periodEnd,
                paidBalance,
                freeBalance + paidBalance
        );
    }

    @Override
    public boolean canConsume(String clerkUserId, String featureCode, long amount) {
        QuotaBalance quota = getUserQuota(clerkUserId, featureCode);
        return quota.totalAvailable() >= amount;
    }

    @Override
    @Transactional(readOnly = true)
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

        if (amount <= 0) {
            throw new IllegalArgumentException("consume amount must be positive");
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
        long paidBalance = quota.getPaidBalance() != null ? quota.getPaidBalance() : 0L;
        long totalAvailable = freeBalance + paidBalance;

        if (totalAvailable < amount) {
            throw new IllegalStateException(
                    String.format("Insufficient quota: need %d, available %d (free=%d, paid=%d)",
                            amount, totalAvailable, freeBalance, paidBalance));
        }

        // 3. 扣减：先扣免费，再扣付费
        long fromFree = Math.min(freeBalance, amount);
        long fromPaid = amount - fromFree;

        long newFreeBalance = freeBalance - fromFree;
        long newPaidBalance = paidBalance - fromPaid;
        boolean fromFreeFlag = fromFree > 0;

        // 4. 更新 user_ai_quotas
        userAiQuotaMapper.update(null, new LambdaUpdateWrapper<UserAiQuotaEntity>()
                .eq(UserAiQuotaEntity::getId, quota.getId())
                .set(UserAiQuotaEntity::getFreeBalance, newFreeBalance)
                .set(UserAiQuotaEntity::getPaidBalance, newPaidBalance)
                .set(UserAiQuotaEntity::getUpdatedAt, now)
                .setSql("version = version + 1"));

        // 5. 写入消费流水（amount 为负数表示扣减）
        Map<String, Object> ctx = bizContext != null ? new HashMap<>(bizContext) : new HashMap<>();
        ctx.put("consumed", amount);
        ctx.put("from_free", fromFreeFlag);
        ctx.put("from_free_amount", fromFree);
        ctx.put("from_paid_amount", fromPaid);

        QuotaLedgerEntity ledger = new QuotaLedgerEntity();
        ledger.setLedgerNo(generateLedgerNo());
        ledger.setClerkUserId(clerkUserId);
        ledger.setFeatureCode(featureCode);
        ledger.setLedgerType(LEDGER_TYPE_CONSUME);
        ledger.setAmount(-amount);
        ledger.setSourceType(sourceType);
        ledger.setSourceId(sourceId);
        ledger.setFreeBalanceAfter(newFreeBalance);
        ledger.setPaidBalanceAfter(newPaidBalance);
        ledger.setBizContext(new com.google.gson.Gson().toJson(ctx));
        ledger.setCreatedAt(now);
        quotaLedgerMapper.insert(ledger);

        log.info("额度消费: clerk_user_id={}, feature={}, amount={}, from_free={}, ledger_id={}",
                clerkUserId, featureCode, amount, fromFreeFlag, ledger.getId());
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

        // 幂等保护：同一 consume 流水若已生成 refund 流水（biz_context.original_ledger_id 命中），跳过。
        // 既兼容 1.0 V1 HumanizerTaskWorker.refundQuota 的重复触发，也兼容 V2 verla 链路 fail+cancel 双回调。
        QuotaLedgerEntity refundExist = quotaLedgerMapper.selectOne(
                new LambdaQueryWrapper<QuotaLedgerEntity>()
                        .eq(QuotaLedgerEntity::getLedgerType, LEDGER_TYPE_REFUND)
                        .like(QuotaLedgerEntity::getBizContext, "\"original_ledger_id\":" + ledgerId)
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

        // 解析 biz_context 判断原扣减来自 free 还是 paid
        boolean fromFree = true;
        Long fromFreeAmount = null;
        Long fromPaidAmount = null;
        if (consumeLedger.getBizContext() != null) {
            try {
                com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(consumeLedger.getBizContext()).getAsJsonObject();
                if (obj.has("from_free")) {
                    fromFree = obj.get("from_free").getAsBoolean();
                }
                if (obj.has("from_free_amount")) {
                    fromFreeAmount = obj.get("from_free_amount").getAsLong();
                }
                if (obj.has("from_paid_amount")) {
                    fromPaidAmount = obj.get("from_paid_amount").getAsLong();
                }
            } catch (Exception e) {
                log.warn("解析 ledger biz_context 失败，默认回滚到免费额度: {}", e.getMessage());
            }
        }

        // 计算回滚到 free 和 paid 的量
        long addToFree = 0L;
        long addToPaid = 0L;
        if (fromFreeAmount != null && fromPaidAmount != null) {
            addToFree = fromFreeAmount;
            addToPaid = fromPaidAmount;
        } else {
            addToFree = fromFree ? refundAmount : 0L;
            addToPaid = fromFree ? 0L : refundAmount;
        }

        // 更新 user_ai_quotas
        UserAiQuotaEntity quota = userAiQuotaMapper.selectOne(
                new LambdaQueryWrapper<UserAiQuotaEntity>()
                        .eq(UserAiQuotaEntity::getClerkUserId, consumeLedger.getClerkUserId())
                        .eq(UserAiQuotaEntity::getFeatureCode, consumeLedger.getFeatureCode())
                        .last("LIMIT 1"));
        if (quota == null) {
            throw new IllegalStateException("User quota not found for refund: " + consumeLedger.getClerkUserId());
        }

        long newFree = (quota.getFreeBalance() != null ? quota.getFreeBalance() : 0L) + addToFree;
        long newPaid = (quota.getPaidBalance() != null ? quota.getPaidBalance() : 0L) + addToPaid;

        userAiQuotaMapper.update(null, new LambdaUpdateWrapper<UserAiQuotaEntity>()
                .eq(UserAiQuotaEntity::getId, quota.getId())
                .set(UserAiQuotaEntity::getFreeBalance, newFree)
                .set(UserAiQuotaEntity::getPaidBalance, newPaid)
                .set(UserAiQuotaEntity::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));

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
        refundLedger.setFreeBalanceAfter(newFree);
        refundLedger.setPaidBalanceAfter(newPaid);
        refundLedger.setBizContext(new com.google.gson.Gson().toJson(ctx));
        refundLedger.setCreatedAt(LocalDateTime.now());
        quotaLedgerMapper.insert(refundLedger);

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
                entity.getPaidBalanceAfter(),
                entity.getCreatedAt(),
                entity.getFeatureCode(),
                quotaUnit
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

        userAiQuotaMapper.update(null, new LambdaUpdateWrapper<UserAiQuotaEntity>()
                .eq(UserAiQuotaEntity::getId, quota.getId())
                .set(UserAiQuotaEntity::getFreeBalance, freeQuotaAmount)
                .set(UserAiQuotaEntity::getFreePeriodStart, periodStart)
                .set(UserAiQuotaEntity::getFreePeriodEnd, periodEnd)
                .set(UserAiQuotaEntity::getUpdatedAt, now)
                .setSql("version = version + 1"));
        quota.setFreeBalance(freeQuotaAmount);
        quota.setFreePeriodStart(periodStart);
        quota.setFreePeriodEnd(periodEnd);

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
