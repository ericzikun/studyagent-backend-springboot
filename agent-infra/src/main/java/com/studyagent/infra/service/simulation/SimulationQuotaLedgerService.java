package com.studyagent.infra.service.simulation;

import com.google.gson.Gson;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.infra.entity.QuotaLedgerEntity;
import com.studyagent.infra.mapper.QuotaLedgerMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes display-only quota ledger rows for subscription lifecycle events.
 * Simulation quota is derived from the plan's assignment quota, not a balance,
 * so these rows must never touch user_ai_quota or entitlement calculations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationQuotaLedgerService {

    private static final Gson GSON = new Gson();
    private static final String FEATURE_CODE = FeatureCode.SIMULATION.getCode();
    private static final String LEDGER_PLAN_CLEAR = "plan_clear";

    private final QuotaLedgerMapper quotaLedgerMapper;
    private final SimulationUsageCounter usageCounter;

    public void recordPlanGrant(String clerkUserId, String subscriptionId, String planCode,
            String ledgerType, String sourceType, String sourceId, String idempotencyPrefix,
            boolean additive, long amount, LocalDateTime periodStart, LocalDateTime periodEnd,
            LocalDateTime now) {
        if (amount <= 0) {
            return;
        }
        String idempotencyKey = idempotencyPrefix + ":" + sourceId + ":"
                + (additive ? "upgrade" : "plan") + ":simulation";
        Map<String, Object> context = baseContext(planCode, subscriptionId, periodStart, periodEnd);
        context.put("display_only", true);
        insertIfAbsent(clerkUserId, ledgerType, amount, sourceType, sourceId, subscriptionId,
                "invoice".equals(sourceType) ? sourceId : null, idempotencyKey, context, now);
    }

    public void recordPlanRefresh(String clerkUserId, String subscriptionId, String planCode,
            long amount, LocalDateTime periodStart, LocalDateTime periodEnd, LocalDateTime now) {
        if (amount <= 0) {
            return;
        }
        // One display row per monthly window regardless of which plan feature triggered it.
        String idempotencyKey = "plan-refresh:" + clerkUserId + ":simulation:" + periodStart;
        Map<String, Object> context = baseContext(planCode, subscriptionId, periodStart, periodEnd);
        context.put("display_only", true);
        context.put("refresh_mode", "annual_monthly_window");
        insertIfAbsent(clerkUserId, "plan_refresh", amount, "subscription", subscriptionId,
                subscriptionId, null, idempotencyKey, context, now);
    }

    /** Clear the display quota remaining at cancellation; no balance is touched. */
    public void recordPlanClear(String clerkUserId, String subscriptionId, String planCode,
            long limit, LocalDateTime periodStart, String periodTimeZone,
            LocalDateTime periodEnd, String idempotencyKey, LocalDateTime now) {
        if (limit <= 0 || clerkUserId == null || clerkUserId.isBlank()) {
            return;
        }
        String key = idempotencyKey + ":simulation";
        if (hasLedger(key)) {
            return;
        }
        LocalDateTime startUtc = SimulationUsageCounter.toUtcWallClock(periodStart, periodTimeZone);
        LocalDateTime endUtc = SimulationUsageCounter.toUtcWallClock(periodEnd, periodTimeZone);
        long used = usageCounter.countSucceededUtc(clerkUserId, startUtc, endUtc);
        long remaining = Math.max(0L, limit - used);
        if (remaining == 0L) {
            return;
        }
        Map<String, Object> context = baseContext(planCode, subscriptionId, periodStart, periodEnd);
        context.put("display_only", true);
        context.put("limit", limit);
        context.put("used", used);
        context.put("remaining", remaining);
        insertIfAbsent(clerkUserId, LEDGER_PLAN_CLEAR, -remaining, "subscription", subscriptionId,
                subscriptionId, null, key, context, now);
    }

    private void insertIfAbsent(String clerkUserId, String ledgerType, long amount,
            String sourceType, String sourceId, String subscriptionId, String invoiceId,
            String idempotencyKey, Map<String, Object> context, LocalDateTime now) {
        if (clerkUserId == null || clerkUserId.isBlank() || hasLedger(idempotencyKey)) {
            return;
        }
        try {
            QuotaLedgerEntity ledger = new QuotaLedgerEntity();
            ledger.setLedgerNo("QL" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
            ledger.setClerkUserId(clerkUserId);
            ledger.setFeatureCode(FEATURE_CODE);
            ledger.setLedgerType(ledgerType);
            ledger.setAmount(amount);
            ledger.setSourceType(sourceType);
            ledger.setSourceId(sourceId);
            ledger.setSubscriptionId(subscriptionId);
            ledger.setInvoiceId(invoiceId);
            ledger.setIdempotencyKey(idempotencyKey);
            ledger.setBizContext(GSON.toJson(context));
            ledger.setCreatedAt(now);
            quotaLedgerMapper.insert(ledger);
        } catch (Exception e) {
            // Display-only row: never block plan fulfillment or cancellation.
            log.warn("[simulation-ledger] failed to write {} for user={} key={}: {}",
                    ledgerType, clerkUserId, idempotencyKey, e.getMessage());
        }
    }

    private boolean hasLedger(String idempotencyKey) {
        return quotaLedgerMapper.selectOne(new LambdaQueryWrapper<QuotaLedgerEntity>()
                .eq(QuotaLedgerEntity::getFeatureCode, FEATURE_CODE)
                .eq(QuotaLedgerEntity::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1")) != null;
    }

    private static Map<String, Object> baseContext(String planCode, String subscriptionId,
            LocalDateTime periodStart, LocalDateTime periodEnd) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("plan_code", planCode);
        context.put("subscription_id", subscriptionId == null ? "" : subscriptionId);
        context.put("quota_period_start", periodStart == null ? "" : periodStart.toString());
        context.put("quota_period_end", periodEnd == null ? "" : periodEnd.toString());
        return context;
    }
}
