package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.Gson;
import com.studyagent.infra.entity.PricingCountryRouteEntity;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.StudyPassEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.entity.SubscriptionPriceRolloutEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.StudyPassMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.SubscriptionPriceRolloutMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.NextCyclePriceChange;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceCollection;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionSchedule;
import com.stripe.net.RequestOptions;
import com.stripe.param.InvoiceListParams;
import com.stripe.param.SubscriptionScheduleCreateParams;
import com.stripe.param.SubscriptionScheduleUpdateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 存量合同下周期换价执行器（plan-a-db §7）。
 *
 * <p>dry-run 逐合同读 Stripe 现状并落库分类（preview/conflict/skipped），顺带回填合同
 * 空列；apply 对 preview/pending 行先比对快照，再以「保留当前段与用户未来段、仅在原
 * 周期结束点替换价格 phase」的方式合并 SubscriptionSchedule（无 prorations），幂等键
 * {@code rollout:{batch}:{sub}:{targetPrice}:{effectiveAt}}；verify 按三态推进；cancel
 * 用写入前快照原样恢复，只撤销本批次价格替换。批次可断点续跑：逐行落库、无整批事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionPriceRolloutService {

    public static final String KIND_SUBSCRIPTION = "subscription";
    public static final String KIND_STUDY_PASS = "study_pass";

    public static final String STATUS_PREVIEW = "preview";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SCHEDULED = "scheduled";
    public static final String STATUS_CONFLICT = "conflict";
    public static final String STATUS_SKIPPED = "skipped";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_SUPERSEDED = "superseded";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_APPLIED = "applied";

    private static final Gson GSON = new Gson();
    private static final String CHANGE_TYPE = "zone_price_rollout";

    private final SubscriptionPriceRolloutMapper rolloutMapper;
    private final UserSubscriptionMapper userSubscriptionMapper;
    private final StudyPassMapper studyPassMapper;
    private final SubscriptionPlanMapper subscriptionPlanMapper;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final ZonePricingCatalog catalog;
    private final ZonePricingSelectionService zonePricing;

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    /** 区外/无国家的默认档；旧合同按币种归属也以它为准（USD 旧合同 → 1.00 档）。 */
    @Value("${billing.regional-pricing.zone-pricing.default-zone:Z100}")
    private String defaultZoneCode;

    public record DryRunSummary(String batchId, String zoneCode, String targetVersion,
                                int scanned, int preview, int conflict, int skipped,
                                int backfilled, int noop) {
    }

    public record ApplySummary(String batchId, int scheduled, int conflict, int failed,
                               int superseded, int noop) {
    }

    public record VerifySummary(String batchId, int applied, int scheduled, int failed, int noop) {
    }

    public record CancelSummary(String batchId, int cancelled, int failed, int noop) {
    }

    /** 单条合同引用：来源行 + 归属证据 + 合同版本列。 */
    private record ContractRef(String contractKind, String clerkUserId, String subscriptionId,
                               Long contractRowId, String sourceVersion, String attribution,
                               boolean zoneColumnBacked) {
    }

    /** dry-run 分类结果；backfill 非空表示需回填合同三列。 */
    private record Classification(String status, String reason, String skuCode, String currency,
                                  String sourcePriceId, String targetPriceId,
                                  LocalDateTime effectiveAt, String snapshotJson,
                                  Map<String, String> backfill) {
    }

    /** Schedule phase 的可重建投影：start/end（epoch 秒）、price、quantity。 */
    public record PhaseSpec(Long start, Long end, String priceId, Long quantity) {
    }

    // ------------------------------------------------------------------
    // dry-run
    // ------------------------------------------------------------------

    public DryRunSummary dryRun(String zoneCode, String targetVersion) {
        requireStripeConfigured();
        if (isBlank(zoneCode) || isBlank(targetVersion)) {
            throw new IllegalArgumentException("zoneCode and targetVersion are required");
        }
        if (catalog.zone(zoneCode).isEmpty()) {
            throw new IllegalArgumentException("Unknown pricing zone: " + zoneCode);
        }
        String batchId = buildBatchId(zoneCode, targetVersion);
        List<ContractRef> contracts = enumerateZoneContracts(zoneCode);
        int preview = 0;
        int conflict = 0;
        int skipped = 0;
        int backfilled = 0;
        int noop = 0;
        for (ContractRef ref : contracts) {
            SubscriptionPriceRolloutEntity existing = findRow(batchId, ref.subscriptionId());
            if (existing != null && List.of(STATUS_SCHEDULED, STATUS_APPLIED,
                    STATUS_CANCELLED, STATUS_SUPERSEDED).contains(existing.getStatus())) {
                noop++;
                continue;
            }
            Classification classification = classify(ref, zoneCode, targetVersion);
            upsertRow(batchId, ref, classification);
            switch (classification.status()) {
                case STATUS_PREVIEW -> preview++;
                case STATUS_CONFLICT -> conflict++;
                default -> skipped++;
            }
            if (classification.backfill() != null) {
                applyContractBackfill(ref, classification.backfill());
                backfilled++;
            }
        }
        return new DryRunSummary(batchId, zoneCode, targetVersion, contracts.size(),
                preview, conflict, skipped, backfilled, noop);
    }

    private Classification classify(ContractRef ref, String zoneCode, String targetVersion) {
        Subscription subscription;
        try {
            subscription = retrieveRolloutSubscription(ref.subscriptionId());
        } catch (StripeException e) {
            log.warn("rollout dry-run: read subscription failed sub={}", ref.subscriptionId(), e);
            return new Classification(STATUS_CONFLICT, "stripe_error:" + e.getMessage(), null, null,
                    null, null, LocalDateTime.now(ZoneOffset.UTC), null, null);
        }
        String currency = subscription.getCurrency() == null
                ? null
                : subscription.getCurrency().toLowerCase();
        SubscriptionItem item = singleItemOrNull(subscription);
        String sourcePriceId = item == null || item.getPrice() == null ? null : item.getPrice().getId();
        if (isBlank(sourcePriceId) || isBlank(currency)) {
            return classificationForState(subscription, ref, STATUS_SKIPPED, "subscription_shape_unreadable", null);
        }
        String sku = resolveSku(sourcePriceId, ref);
        if (isBlank(sku)) {
            return classificationForState(subscription, ref, STATUS_SKIPPED, "price_unresolved", currency);
        }
        var targetBinding = catalog.contractBinding(zoneCode, targetVersion, sku).orElse(null);
        if (targetBinding == null) {
            return classificationForState(subscription, ref, STATUS_SKIPPED, "no_target_binding:" + sku, currency);
        }
        if (!Boolean.TRUE.equals(targetBinding.getIsActive())) {
            return classificationForState(subscription, ref, STATUS_SKIPPED, "target_binding_inactive", currency);
        }
        if (isBlank(targetBinding.getStripePriceId())) {
            return classificationForState(subscription, ref, STATUS_SKIPPED, "target_price_not_backfilled", currency);
        }
        Integer targetAmount = catalog.currencyAmounts(targetBinding).orElse(Map.of()).get(currency);
        if (targetAmount == null) {
            String reason = "usd".equals(currency)
                    ? "usd_contract_no_target"
                    : "no_target_amount:" + currency;
            return classificationForState(subscription, ref, STATUS_SKIPPED, reason, currency);
        }
        if (Objects.equals(sourcePriceId, targetBinding.getStripePriceId())) {
            return classificationForState(subscription, ref, STATUS_SKIPPED, "already_on_target", currency);
        }
        if (targetVersion.equals(ref.sourceVersion())) {
            return classificationForState(subscription, ref, STATUS_SKIPPED, "same_version", currency);
        }
        String status = subscription.getStatus();
        if (!"active".equals(status) && !"trialing".equals(status)) {
            return classificationForState(subscription, ref, STATUS_CONFLICT, "status:" + status, currency);
        }
        if (Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd())) {
            return classificationForState(subscription, ref, STATUS_CONFLICT, "cancelling_at_period_end", currency);
        }
        Long periodEnd = subscription.getCurrentPeriodEnd();
        if (periodEnd == null || periodEnd <= Instant.now().getEpochSecond()) {
            return classificationForState(subscription, ref, STATUS_CONFLICT, "window_passed", currency);
        }
        if (hasUpcomingInvoice(subscription.getId())) {
            return classificationForState(subscription, ref, STATUS_CONFLICT, "already_invoiced_next_period", currency);
        }
        Map<String, String> backfill = null;
        if (ref.zoneColumnBacked() && isBlank(ref.sourceVersion())) {
            // 已属区但版本列缺失的合同：dry-run 顺带回填版本列。
            // 旧价合同不回填 zone/版本——合同三列一旦有值，会员目录会立刻按新价展示，
            // 违反「到期前仍显示旧价」；由换价生效后的 webhook 反查再回填。
            backfill = Map.of("zoneCode", zoneCode, "priceVersion", "legacy", "currency", currency);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schedule_id", nullSafe(subscription.getSchedule()));
        snapshot.put("cancel_at_period_end", Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()));
        snapshot.put("current_period_end", periodEnd);
        snapshot.put("status", status);
        snapshot.put("price_id", sourcePriceId);
        return new Classification(STATUS_PREVIEW, null, sku, currency, sourcePriceId,
                targetBinding.getStripePriceId(), fromEpoch(periodEnd), GSON.toJson(snapshot), backfill);
    }

    private Classification classificationForState(Subscription subscription, ContractRef ref,
                                                  String status, String reason, String currency) {
        Long periodEnd = subscription.getCurrentPeriodEnd();
        return new Classification(status, reason, null, currency, null, null,
                periodEnd == null ? LocalDateTime.now(ZoneOffset.UTC) : fromEpoch(periodEnd),
                null, null);
    }

    // ------------------------------------------------------------------
    // apply
    // ------------------------------------------------------------------

    public ApplySummary apply(String batchId) {
        requireStripeConfigured();
        if (isBlank(batchId)) {
            throw new IllegalArgumentException("batchId is required");
        }
        List<SubscriptionPriceRolloutEntity> rows = rolloutMapper.selectList(
                new LambdaQueryWrapper<SubscriptionPriceRolloutEntity>()
                        .eq(SubscriptionPriceRolloutEntity::getBatchId, batchId)
                        .in(SubscriptionPriceRolloutEntity::getStatus,
                                List.of(STATUS_PREVIEW, STATUS_PENDING))
                        .orderByAsc(SubscriptionPriceRolloutEntity::getId));
        int scheduled = 0;
        int conflict = 0;
        int failed = 0;
        int superseded = 0;
        int noop = 0;
        for (SubscriptionPriceRolloutEntity row : rows) {
            try {
                ApplyOutcome outcome = applySingle(row);
                switch (outcome) {
                    case SCHEDULED -> scheduled++;
                    case CONFLICT -> conflict++;
                    case FAILED -> failed++;
                    case SUPERSEDED -> superseded++;
                    case NOOP -> noop++;
                }
            } catch (StripeException e) {
                markRow(row, STATUS_FAILED, "stripe_error:" + e.getMessage(), null);
                failed++;
                log.warn("rollout apply failed: batch={}, sub={}", batchId, row.getStripeSubscriptionId(), e);
            } catch (RuntimeException e) {
                markRow(row, STATUS_FAILED, e.getClass().getSimpleName() + ":" + e.getMessage(), null);
                failed++;
                log.warn("rollout apply failed: batch={}, sub={}", batchId, row.getStripeSubscriptionId(), e);
            }
        }
        return new ApplySummary(batchId, scheduled, conflict, failed, superseded, noop);
    }

    enum ApplyOutcome {
        SCHEDULED, CONFLICT, FAILED, SUPERSEDED, NOOP
    }

    private ApplyOutcome applySingle(SubscriptionPriceRolloutEntity row) throws StripeException {
        Subscription subscription = retrieveRolloutSubscription(row.getStripeSubscriptionId());
        SubscriptionItem item = singleItemOrNull(subscription);
        String livePriceId = item == null || item.getPrice() == null ? null : item.getPrice().getId();
        if (!Objects.equals(livePriceId, row.getSourcePriceId())) {
            markRow(row, STATUS_CONFLICT, "snapshot_stale:price_changed", null);
            return ApplyOutcome.CONFLICT;
        }
        if (Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd())) {
            markRow(row, STATUS_CONFLICT, "snapshot_stale:cancelling", null);
            return ApplyOutcome.CONFLICT;
        }
        Long periodEnd = subscription.getCurrentPeriodEnd();
        if (periodEnd == null || !Objects.equals(periodEnd,
                row.getEffectiveAt() == null ? null : row.getEffectiveAt().toEpochSecond(ZoneOffset.UTC))) {
            markRow(row, STATUS_CONFLICT, "snapshot_stale:period_changed", null);
            return ApplyOutcome.CONFLICT;
        }
        String status = subscription.getStatus();
        if (!"active".equals(status) && !"trialing".equals(status)) {
            markRow(row, STATUS_CONFLICT, "snapshot_stale:status:" + status, null);
            return ApplyOutcome.CONFLICT;
        }
        if (hasUpcomingInvoice(subscription.getId())) {
            markRow(row, STATUS_CONFLICT, "snapshot_stale:already_invoiced", null);
            return ApplyOutcome.CONFLICT;
        }
        // 超时重试防重复：重读实际 Schedule，目标 phase 已在则直接视为已安排。
        String existingScheduleId = subscription.getSchedule();
        SubscriptionSchedule schedule = isBlank(existingScheduleId)
                ? null
                : retrieveRolloutScheduleOrNull(existingScheduleId);
        if (schedule != null && scheduleHasTargetPhase(schedule, row.getTargetPriceId(),
                row.getEffectiveAt().toEpochSecond(ZoneOffset.UTC))) {
            markRow(row, STATUS_SCHEDULED, "already_written", row.getSnapshotJson());
            return ApplyOutcome.NOOP;
        }
        // 同合同更晚批次的任务先作废（v2 让位 v3）。
        int supersededCount = supersedeOtherBatches(row);
        String effectiveEpoch = Long.toString(periodEnd);
        String idempotencyKey = String.join(":", "rollout", row.getBatchId(),
                row.getStripeSubscriptionId(), row.getTargetPriceId(), effectiveEpoch);
        Long quantity = item.getQuantity() == null ? 1L : item.getQuantity();
        if (schedule == null) {
            schedule = createRolloutSchedule(subscription.getId(),
                    "rollout-create:" + row.getBatchId() + ":" + row.getStripeSubscriptionId() + ":" + effectiveEpoch);
        }
        List<PhaseSpec> preimage = schedulePhases(schedule);
        if (schedule.getPhases() != null && !schedule.getPhases().isEmpty() && preimage.isEmpty()) {
            // Schedule 存在但形状不可解析（多 item 等）：宁可失败也不能整体重写——用户未来段会被抹掉。
            markRow(row, STATUS_CONFLICT, "schedule_shape_unreadable", null);
            return ApplyOutcome.CONFLICT;
        }
        if (foreignFuturePhaseExists(schedule, preimage, periodEnd,
                row.getSourcePriceId(), row.getStripeSubscriptionId())) {
            // 用户在途的未来变更（转正/降级段）不得被换价覆盖：留冲突行人工处理。
            markRow(row, STATUS_CONFLICT, "user_future_change_pending", null);
            return ApplyOutcome.CONFLICT;
        }
        int replacedIndex = indexOfPhaseAt(preimage, periodEnd);
        List<PhaseSpec> merged = mergePhases(schedule, subscription, row, quantity, periodEnd);
        Map<String, String> metadata = new LinkedHashMap<>();
        if (schedule.getMetadata() != null) {
            metadata.putAll(schedule.getMetadata());
        }
        metadata.put("change_type", CHANGE_TYPE);
        metadata.put("rollout_batch", row.getBatchId());
        metadata.put("rollout_target_version", row.getTargetVersion());
        metadata.put("clerk_user_id", row.getClerkUserId());
        SubscriptionScheduleUpdateParams params = buildScheduleUpdateParams(merged, metadata,
                row.getCurrency(), row.getTargetPriceId());
        SubscriptionSchedule updated = updateRolloutSchedule(schedule, params, idempotencyKey);
        Map<String, Object> preimageSnapshot = new LinkedHashMap<>();
        preimageSnapshot.put("schedule_id", updated.getId());
        preimageSnapshot.put("preimage_phases", preimage);
        preimageSnapshot.put("replaced_phase_index", replacedIndex);
        markRow(row, STATUS_SCHEDULED, null, GSON.toJson(preimageSnapshot));
        return supersededCount > 0 ? ApplyOutcome.SUPERSEDED : ApplyOutcome.SCHEDULED;
    }

    private int supersedeOtherBatches(SubscriptionPriceRolloutEntity row) {
        List<SubscriptionPriceRolloutEntity> others = rolloutMapper.selectList(
                new LambdaQueryWrapper<SubscriptionPriceRolloutEntity>()
                        .eq(SubscriptionPriceRolloutEntity::getStripeSubscriptionId,
                                row.getStripeSubscriptionId())
                        .ne(SubscriptionPriceRolloutEntity::getBatchId, row.getBatchId())
                        .in(SubscriptionPriceRolloutEntity::getStatus,
                                List.of(STATUS_PENDING, STATUS_SCHEDULED)));
        for (SubscriptionPriceRolloutEntity other : others) {
            markRow(other, STATUS_SUPERSEDED, "superseded_by:" + row.getBatchId(), other.getSnapshotJson());
        }
        return others.size();
    }

    /**
     * 合并规则：保留当前段与用户未来段（转正/降级/取消语义/日期），仅在原周期结束点
     * 替换价格 phase；无 prorations。
     */
    private List<PhaseSpec> mergePhases(SubscriptionSchedule schedule, Subscription subscription,
                                        SubscriptionPriceRolloutEntity row, Long quantity,
                                        Long effective) {
        List<PhaseSpec> existing = schedulePhases(schedule);
        if (existing.isEmpty()) {
            Long currentStart = subscription.getCurrentPeriodStart() == null
                    ? Instant.now().getEpochSecond()
                    : subscription.getCurrentPeriodStart();
            return List.of(
                    new PhaseSpec(currentStart, effective, row.getSourcePriceId(), quantity),
                    new PhaseSpec(effective, null, row.getTargetPriceId(), quantity));
        }
        List<PhaseSpec> merged = new ArrayList<>();
        boolean targetInserted = false;
        for (PhaseSpec phase : existing) {
            if (phase.end() != null && phase.end() <= effective) {
                merged.add(phase);
            } else if (phase.start() >= effective) {
                if (!targetInserted) {
                    if (phase.start().equals(effective)) {
                        merged.add(new PhaseSpec(effective, phase.end(), row.getTargetPriceId(), phase.quantity()));
                    } else {
                        merged.add(new PhaseSpec(effective, phase.start(), row.getTargetPriceId(), quantity));
                        merged.add(phase);
                    }
                } else {
                    merged.add(phase);
                }
                targetInserted = true;
            } else {
                // 跨越生效点的当前段：截断到生效点，随后插入目标段（下一段起点为准）。
                merged.add(new PhaseSpec(phase.start(), effective, phase.priceId(), phase.quantity()));
                if (!targetInserted) {
                    Long nextStart = nextPhaseStartAfter(existing, effective);
                    merged.add(new PhaseSpec(effective, nextStart, row.getTargetPriceId(), quantity));
                    targetInserted = true;
                }
            }
        }
        if (!targetInserted) {
            merged.add(new PhaseSpec(effective, null, row.getTargetPriceId(), quantity));
        }
        return merged;
    }

    private Long nextPhaseStartAfter(List<PhaseSpec> phases, Long effective) {
        for (PhaseSpec phase : phases) {
            if (phase.start() > effective) {
                return phase.start();
            }
        }
        return null;
    }

    /** 生效点之后存在非源价的未来段且无法证明是历史 rollout 写入时，视为用户在途变更。 */
    private boolean foreignFuturePhaseExists(SubscriptionSchedule schedule, List<PhaseSpec> preimage,
                                             Long effective, String sourcePriceId, String subscriptionId) {
        List<PhaseSpec> futureForeign = new ArrayList<>();
        for (PhaseSpec phase : preimage) {
            if (phase.start() != null && phase.start() >= effective
                    && !Objects.equals(phase.priceId(), sourcePriceId)) {
                futureForeign.add(phase);
            }
        }
        if (futureForeign.isEmpty()) {
            return false;
        }
        String priorBatch = schedule.getMetadata() == null ? null : schedule.getMetadata().get("rollout_batch");
        if (!hasText(priorBatch)) {
            return true;
        }
        for (PhaseSpec phase : futureForeign) {
            Long recorded = rolloutMapper.selectCount(new LambdaQueryWrapper<SubscriptionPriceRolloutEntity>()
                    .eq(SubscriptionPriceRolloutEntity::getBatchId, priorBatch)
                    .eq(SubscriptionPriceRolloutEntity::getStripeSubscriptionId, subscriptionId)
                    .eq(SubscriptionPriceRolloutEntity::getTargetPriceId, phase.priceId())
                    .in(SubscriptionPriceRolloutEntity::getStatus,
                            List.of(STATUS_SCHEDULED, STATUS_APPLIED, STATUS_SUPERSEDED)));
            if (recorded == null || recorded == 0) {
                return true;
            }
        }
        return false;
    }

    private int indexOfPhaseAt(List<PhaseSpec> phases, Long start) {
        for (int i = 0; i < phases.size(); i++) {
            if (phases.get(i).start().equals(start)) {
                return i;
            }
        }
        return -1;
    }

    private SubscriptionScheduleUpdateParams buildScheduleUpdateParams(List<PhaseSpec> phases,
                                                                       Map<String, String> metadata,
                                                                       String targetCurrency,
                                                                       String targetPriceId) {
        SubscriptionScheduleUpdateParams.Builder builder = SubscriptionScheduleUpdateParams.builder()
                .setEndBehavior(SubscriptionScheduleUpdateParams.EndBehavior.RELEASE)
                .setProrationBehavior(SubscriptionScheduleUpdateParams.ProrationBehavior.NONE);
        for (PhaseSpec phase : phases) {
            SubscriptionScheduleUpdateParams.Phase.Builder phaseBuilder =
                    SubscriptionScheduleUpdateParams.Phase.builder()
                            .setStartDate(phase.start())
                            .setProrationBehavior(SubscriptionScheduleUpdateParams.Phase.ProrationBehavior.NONE)
                            .addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder()
                                    .setPrice(phase.priceId())
                                    .setQuantity(phase.quantity())
                                    .build());
            if (phase.end() != null) {
                phaseBuilder.setEndDate(phase.end());
            }
            if (targetPriceId.equals(phase.priceId()) && targetCurrency != null) {
                phaseBuilder.setCurrency(targetCurrency);
            }
            builder.addPhase(phaseBuilder.build());
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            builder.putMetadata(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private List<PhaseSpec> schedulePhases(SubscriptionSchedule schedule) {
        List<PhaseSpec> phases = new ArrayList<>();
        if (schedule == null || schedule.getPhases() == null) {
            return phases;
        }
        for (SubscriptionSchedule.Phase phase : schedule.getPhases()) {
            if (phase.getItems() == null || phase.getItems().size() != 1
                    || phase.getItems().get(0).getPrice() == null) {
                continue;
            }
            SubscriptionSchedule.Phase.Item item = phase.getItems().get(0);
            phases.add(new PhaseSpec(phase.getStartDate(), phase.getEndDate(),
                    item.getPrice(), item.getQuantity() == null ? 1L : item.getQuantity()));
        }
        return phases;
    }

    private boolean scheduleHasTargetPhase(SubscriptionSchedule schedule, String targetPriceId, Long effective) {
        for (PhaseSpec phase : schedulePhases(schedule)) {
            if (targetPriceId.equals(phase.priceId())
                    && (effective == null || effective.equals(phase.start()))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // verify
    // ------------------------------------------------------------------

    public VerifySummary verify(String batchId) {
        requireStripeConfigured();
        List<SubscriptionPriceRolloutEntity> rows = rolloutMapper.selectList(
                new LambdaQueryWrapper<SubscriptionPriceRolloutEntity>()
                        .eq(SubscriptionPriceRolloutEntity::getBatchId, batchId)
                        .in(SubscriptionPriceRolloutEntity::getStatus,
                                List.of(STATUS_SCHEDULED, STATUS_PENDING, STATUS_PREVIEW))
                        .orderByAsc(SubscriptionPriceRolloutEntity::getId));
        int applied = 0;
        int scheduled = 0;
        int failed = 0;
        int noop = 0;
        for (SubscriptionPriceRolloutEntity row : rows) {
            if (!STATUS_SCHEDULED.equals(row.getStatus())) {
                markRow(row, STATUS_FAILED, "not_applied:" + row.getStatus(), row.getSnapshotJson());
                failed++;
                continue;
            }
            try {
                Subscription subscription = retrieveRolloutSubscription(row.getStripeSubscriptionId());
                SubscriptionItem item = singleItemOrNull(subscription);
                String livePriceId = item == null || item.getPrice() == null ? null : item.getPrice().getId();
                if (Objects.equals(livePriceId, row.getTargetPriceId())) {
                    // 已生效（含已付款周期：发票随 phase 切换自动落在目标价上）。
                    markRow(row, STATUS_APPLIED, "phase_effective", row.getSnapshotJson());
                    applied++;
                    continue;
                }
                SubscriptionSchedule schedule = isBlank(subscription.getSchedule())
                        ? null
                        : retrieveRolloutScheduleOrNull(subscription.getSchedule());
                if (schedule != null && scheduleHasTargetPhase(schedule, row.getTargetPriceId(),
                        row.getEffectiveAt() == null ? null
                                : row.getEffectiveAt().toEpochSecond(ZoneOffset.UTC))) {
                    scheduled++;
                    continue;
                }
                markRow(row, STATUS_FAILED, "schedule_missing", row.getSnapshotJson());
                failed++;
            } catch (StripeException e) {
                markRow(row, STATUS_FAILED, "stripe_error:" + e.getMessage(), row.getSnapshotJson());
                failed++;
                log.warn("rollout verify failed: batch={}, sub={}", batchId, row.getStripeSubscriptionId(), e);
            }
        }
        return new VerifySummary(batchId, applied, scheduled, failed, noop);
    }

    // ------------------------------------------------------------------
    // cancel
    // ------------------------------------------------------------------

    public CancelSummary cancel(String batchId) {
        requireStripeConfigured();
        List<SubscriptionPriceRolloutEntity> rows = rolloutMapper.selectList(
                new LambdaQueryWrapper<SubscriptionPriceRolloutEntity>()
                        .eq(SubscriptionPriceRolloutEntity::getBatchId, batchId)
                        .in(SubscriptionPriceRolloutEntity::getStatus,
                                List.of(STATUS_SCHEDULED, STATUS_PENDING, STATUS_PREVIEW))
                        .orderByAsc(SubscriptionPriceRolloutEntity::getId));
        int cancelled = 0;
        int failed = 0;
        int noop = 0;
        for (SubscriptionPriceRolloutEntity row : rows) {
            if (!STATUS_SCHEDULED.equals(row.getStatus())) {
                markRow(row, STATUS_CANCELLED, "never_written", row.getSnapshotJson());
                cancelled++;
                continue;
            }
            try {
                Map<String, Object> preimage = row.getSnapshotJson() == null
                        ? null
                        : GSON.fromJson(row.getSnapshotJson(), Map.class);
                if (preimage == null || !preimage.containsKey("preimage_phases")) {
                    markRow(row, STATUS_FAILED, "preimage_missing", row.getSnapshotJson());
                    failed++;
                    continue;
                }
                Object scheduleIdRaw = preimage.get("schedule_id");
                if (scheduleIdRaw == null || String.valueOf(scheduleIdRaw).isBlank()
                        || "null".equals(String.valueOf(scheduleIdRaw))) {
                    markRow(row, STATUS_CANCELLED, "schedule_released", row.getSnapshotJson());
                    cancelled++;
                    continue;
                }
                SubscriptionSchedule schedule = retrieveRolloutScheduleOrNull(String.valueOf(scheduleIdRaw));
                if (schedule == null) {
                    markRow(row, STATUS_CANCELLED, "schedule_released", row.getSnapshotJson());
                    cancelled++;
                    continue;
                }
                List<PhaseSpec> phases = readPreimagePhases(preimage.get("preimage_phases"));
                Map<String, String> metadata = new LinkedHashMap<>();
                if (schedule.getMetadata() != null) {
                    metadata.putAll(schedule.getMetadata());
                }
                metadata.remove("rollout_batch");
                metadata.remove("rollout_target_version");
                if (CHANGE_TYPE.equals(metadata.get("change_type"))) {
                    metadata.put("change_type", "zone_price_rollout_cancelled");
                }
                SubscriptionScheduleUpdateParams params = buildScheduleUpdateParams(
                        phases, metadata, null, "");
                updateRolloutSchedule(schedule, params,
                        "rollout-cancel:" + row.getBatchId() + ":" + row.getStripeSubscriptionId());
                markRow(row, STATUS_CANCELLED, null, row.getSnapshotJson());
                cancelled++;
            } catch (StripeException e) {
                markRow(row, STATUS_FAILED, "stripe_error:" + e.getMessage(), row.getSnapshotJson());
                failed++;
                log.warn("rollout cancel failed: batch={}, sub={}", batchId, row.getStripeSubscriptionId(), e);
            } catch (RuntimeException e) {
                markRow(row, STATUS_FAILED, e.getClass().getSimpleName() + ":" + e.getMessage(), row.getSnapshotJson());
                failed++;
                log.warn("rollout cancel failed: batch={}, sub={}", batchId, row.getStripeSubscriptionId(), e);
            }
        }
        return new CancelSummary(batchId, cancelled, failed, noop);
    }

    @SuppressWarnings("unchecked")
    private List<PhaseSpec> readPreimagePhases(Object raw) {
        List<PhaseSpec> phases = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return phases;
        }
        for (Object entryRaw : list) {
            if (!(entryRaw instanceof Map<?, ?> entry)) {
                continue;
            }
            Map<String, Object> entryMap = (Map<String, Object>) entry;
            Long start = toLong(entryMap.get("start"));
            Long end = toLong(entryMap.get("end"));
            String priceId = entryMap.get("priceId") == null ? null : String.valueOf(entryMap.get("priceId"));
            Long quantity = toLong(entryMap.get("quantity"));
            if (start == null || priceId == null) {
                continue;
            }
            phases.add(new PhaseSpec(start, end, priceId, quantity == null ? 1L : quantity));
        }
        return phases;
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 合同枚举与归属
    // ------------------------------------------------------------------

    private List<ContractRef> enumerateZoneContracts(String zoneCode) {
        List<ContractRef> refs = new ArrayList<>();
        List<UserSubscriptionEntity> zoneRows = userSubscriptionMapper.selectList(
                new LambdaQueryWrapper<UserSubscriptionEntity>()
                        .eq(UserSubscriptionEntity::getPricingZone, zoneCode)
                        .isNotNull(UserSubscriptionEntity::getStripeSubscriptionId));
        for (UserSubscriptionEntity row : zoneRows) {
            if (isRealSubscriptionRef(row.getStripeSubscriptionId())) {
                refs.add(new ContractRef(KIND_SUBSCRIPTION, row.getClerkUserId(),
                        row.getStripeSubscriptionId(), row.getId(), row.getPriceVersion(),
                        "zone_column", true));
            }
        }
        List<StudyPassEntity> passRows = studyPassMapper.selectList(
                new LambdaQueryWrapper<StudyPassEntity>()
                        .eq(StudyPassEntity::getPricingZone, zoneCode)
                        .eq(StudyPassEntity::getStatus, "active")
                        .isNotNull(StudyPassEntity::getStripeSubscriptionId));
        for (StudyPassEntity pass : passRows) {
            if (isRealSubscriptionRef(pass.getStripeSubscriptionId())) {
                refs.add(new ContractRef(KIND_STUDY_PASS, pass.getClerkUserId(),
                        pass.getStripeSubscriptionId(), pass.getId(), pass.getPriceVersion(),
                        "zone_column", true));
            }
        }
        // 巴西老合同按订单证据归属：pricing_zone 为空、订阅订单以本区路由币种支付。
        for (String currency : legacyEvidenceCurrencies(zoneCode)) {
            List<RechargeOrderEntity> orders = rechargeOrderMapper.selectList(
                    new LambdaQueryWrapper<RechargeOrderEntity>()
                            .select(RechargeOrderEntity::getClerkUserId)
                            .eq(RechargeOrderEntity::getCurrency, currency)
                            .likeRight(RechargeOrderEntity::getOrderType, "subscription")
                            .in(RechargeOrderEntity::getStatus, List.of("completed", "paid"))
                            .last("LIMIT 5000"));
            TreeSet<String> userIds = new TreeSet<>();
            for (RechargeOrderEntity order : orders) {
                if (hasText(order.getClerkUserId())) {
                    userIds.add(order.getClerkUserId());
                }
            }
            for (String userId : userIds) {
                UserSubscriptionEntity row = userSubscriptionMapper.selectOne(
                        new LambdaQueryWrapper<UserSubscriptionEntity>()
                                .eq(UserSubscriptionEntity::getClerkUserId, userId)
                                .isNull(UserSubscriptionEntity::getPricingZone)
                                .isNotNull(UserSubscriptionEntity::getStripeSubscriptionId)
                                .last("LIMIT 1"));
                if (row != null && isRealSubscriptionRef(row.getStripeSubscriptionId())
                        && refs.stream().noneMatch(ref -> ref.subscriptionId().equals(row.getStripeSubscriptionId()))) {
                    refs.add(new ContractRef(KIND_SUBSCRIPTION, row.getClerkUserId(),
                            row.getStripeSubscriptionId(), row.getId(), "legacy",
                            "order_evidence", false));
                }
            }
        }
        // 默认区（Z100）：USD 老合同统一换到 1.00 档，不依赖历史订单证据（部分老订阅没有订单行）。
        // 币种为 NULL 的行也纳入，分类阶段按 Stripe 实际币种校验；非 USD 会在目标金额校验时跳过。
        String fallbackZone = defaultZoneCode == null || defaultZoneCode.isBlank()
                ? "Z100" : defaultZoneCode.trim();
        if (fallbackZone.equals(zoneCode)) {
            List<UserSubscriptionEntity> legacyRows = userSubscriptionMapper.selectList(
                    new LambdaQueryWrapper<UserSubscriptionEntity>()
                            .isNull(UserSubscriptionEntity::getPricingZone)
                            .isNotNull(UserSubscriptionEntity::getStripeSubscriptionId)
                            .in(UserSubscriptionEntity::getStatus,
                                    List.of("active", "trialing", "past_due", "unpaid"))
                            .and(w -> w.isNull(UserSubscriptionEntity::getCurrency)
                                    .or().eq(UserSubscriptionEntity::getCurrency, "usd")));
            for (UserSubscriptionEntity row : legacyRows) {
                if (isRealSubscriptionRef(row.getStripeSubscriptionId())
                        && refs.stream().noneMatch(ref -> ref.subscriptionId().equals(row.getStripeSubscriptionId()))) {
                    refs.add(new ContractRef(KIND_SUBSCRIPTION, row.getClerkUserId(),
                            row.getStripeSubscriptionId(), row.getId(), "legacy",
                            "legacy_contract", false));
                }
            }
        }
        return refs;
    }

    /** 旧合同按订单证据归属的币种：本区路由币种 + 默认区（Z100）的 USD。 */
    private List<String> legacyEvidenceCurrencies(String zoneCode) {
        java.util.LinkedHashSet<String> currencies = new java.util.LinkedHashSet<>();
        for (PricingCountryRouteEntity route : catalog.snapshot().routes().values()) {
            if (zoneCode.equals(route.getZoneCode()) && hasText(route.getCurrency())) {
                currencies.add(route.getCurrency().toLowerCase());
            }
        }
        String fallbackZone = defaultZoneCode == null || defaultZoneCode.isBlank()
                ? "Z100" : defaultZoneCode.trim();
        if (fallbackZone.equals(zoneCode)) {
            // 旧 $79.99 合同绝大多数是 USD：默认区按 USD 订单证据归属。
            currencies.add("usd");
        }
        return new ArrayList<>(currencies);
    }

    private String resolveSku(String priceId, ContractRef ref) {
        String sku = zonePricing.skuCodeForPriceId(priceId).orElse(null);
        if (sku == null) {
            SubscriptionPlanEntity plan = subscriptionPlanMapper.selectOne(
                    new LambdaQueryWrapper<SubscriptionPlanEntity>()
                            .eq(SubscriptionPlanEntity::getStripePriceId, priceId)
                            .last("LIMIT 1"));
            sku = plan == null ? null : plan.getPlanCode();
        }
        if (sku == null) {
            return null;
        }
        // kind 必须与合同种类一致：订阅 webhook 上来的 study_pass Price 不可混入。
        // 注册表外的 Price 是 legacy 订阅套餐价（刚从 subscription_plan 解析而来），默认 kind 即 subscription_plan。
        String bindingKind = catalog.bindingById(priceId)
                .map(binding -> binding.getSkuKind())
                .orElse(ZonePricingSelectionService.KIND_SUBSCRIPTION_PLAN);
        if (KIND_STUDY_PASS.equals(ref.contractKind())) {
            return ZonePricingSelectionService.KIND_STUDY_PASS.equals(bindingKind) ? sku : null;
        }
        return ZonePricingSelectionService.KIND_SUBSCRIPTION_PLAN.equals(bindingKind) ? sku : null;
    }

    private void applyContractBackfill(ContractRef ref, Map<String, String> backfill) {
        if (!KIND_SUBSCRIPTION.equals(ref.contractKind()) || ref.contractRowId() == null) {
            return;
        }
        userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscriptionEntity>()
                .eq(UserSubscriptionEntity::getId, ref.contractRowId())
                .set(UserSubscriptionEntity::getPricingZone, backfill.get("zoneCode"))
                .set(UserSubscriptionEntity::getPriceVersion, backfill.get("priceVersion"))
                .set(UserSubscriptionEntity::getCurrency, backfill.get("currency"))
                .set(UserSubscriptionEntity::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
    }

    // ------------------------------------------------------------------
    // 落库
    // ------------------------------------------------------------------

    private void upsertRow(String batchId, ContractRef ref, Classification classification) {
        SubscriptionPriceRolloutEntity existing = findRow(batchId, ref.subscriptionId());
        String effectiveEpoch = classification.effectiveAt() == null
                ? null
                : Long.toString(classification.effectiveAt().toEpochSecond(ZoneOffset.UTC));
        String idempotencyKey = String.join(":", "rollout", batchId, ref.subscriptionId(),
                nullSafe(classification.targetPriceId()), nullSafe(effectiveEpoch));
        if (existing != null) {
            // 重跑 dry-run 时状态可能从 conflict/skipped 变成 preview，
            // entity 的 NOT_NULL 更新策略会跳过 null，导致旧的 status_reason 残留。
            rolloutMapper.update(null, new LambdaUpdateWrapper<SubscriptionPriceRolloutEntity>()
                    .eq(SubscriptionPriceRolloutEntity::getId, existing.getId())
                    .set(SubscriptionPriceRolloutEntity::getStatus, classification.status())
                    .set(SubscriptionPriceRolloutEntity::getStatusReason, classification.reason())
                    .set(SubscriptionPriceRolloutEntity::getCurrency, classification.currency())
                    .set(SubscriptionPriceRolloutEntity::getSourcePriceId, classification.sourcePriceId())
                    .set(SubscriptionPriceRolloutEntity::getTargetPriceId, classification.targetPriceId())
                    .set(SubscriptionPriceRolloutEntity::getEffectiveAt, classification.effectiveAt())
                    .set(SubscriptionPriceRolloutEntity::getSnapshotJson, classification.snapshotJson())
                    .set(SubscriptionPriceRolloutEntity::getIdempotencyKey, idempotencyKey));
            return;
        }
        SubscriptionPriceRolloutEntity row = new SubscriptionPriceRolloutEntity();
        row.setBatchId(batchId);
        row.setContractKind(ref.contractKind());
        row.setClerkUserId(ref.clerkUserId());
        row.setStripeSubscriptionId(ref.subscriptionId());
        row.setZoneCode(batchZone(batchId));
        row.setCurrency(classification.currency());
        row.setSourcePriceId(classification.sourcePriceId());
        row.setSourceVersion(ref.sourceVersion());
        row.setTargetPriceId(classification.targetPriceId());
        row.setTargetVersion(batchTargetVersion(batchId));
        row.setEffectiveAt(classification.effectiveAt());
        row.setStatus(classification.status());
        row.setStatusReason(classification.reason());
        row.setSnapshotJson(classification.snapshotJson());
        row.setIdempotencyKey(idempotencyKey);
        rolloutMapper.insert(row);
    }

    private void markRow(SubscriptionPriceRolloutEntity row, String status, String reason, String snapshotJson) {
        row.setStatus(status);
        row.setStatusReason(reason);
        if (snapshotJson != null) {
            row.setSnapshotJson(snapshotJson);
        }
        rolloutMapper.updateById(row);
    }

    private SubscriptionPriceRolloutEntity findRow(String batchId, String subscriptionId) {
        return rolloutMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPriceRolloutEntity>()
                        .eq(SubscriptionPriceRolloutEntity::getBatchId, batchId)
                        .eq(SubscriptionPriceRolloutEntity::getStripeSubscriptionId, subscriptionId)
                        .last("LIMIT 1"));
    }

    public String buildBatchId(String zoneCode, String targetVersion) {
        String versionTag = targetVersion.replace("regional-", "");
        return "rollout-" + zoneCode + "-" + versionTag + "-"
                + LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private String batchZone(String batchId) {
        String[] parts = batchId.split("-");
        return parts.length > 1 ? parts[1] : batchId;
    }

    private String batchTargetVersion(String batchId) {
        // rollout-{zone}-{versionTag...}-{date}：去掉首尾两段，中间即版本标记。
        String[] parts = batchId.split("-");
        if (parts.length < 4) {
            return null;
        }
        return "regional-" + String.join("-", java.util.Arrays.copyOfRange(parts, 2, parts.length - 1));
    }

    // ------------------------------------------------------------------
    // Stripe 访问（protected：测试覆写）
    // ------------------------------------------------------------------

    protected Subscription retrieveRolloutSubscription(String subscriptionId) throws StripeException {
        return Subscription.retrieve(subscriptionId);
    }

    protected SubscriptionSchedule retrieveRolloutScheduleOrNull(String scheduleId) throws StripeException {
        try {
            SubscriptionSchedule schedule = SubscriptionSchedule.retrieve(scheduleId);
            return "canceled".equals(schedule.getStatus()) ? null : schedule;
        } catch (InvalidRequestException e) {
            return null;
        }
    }

    protected SubscriptionSchedule createRolloutSchedule(String subscriptionId, String idempotencyKey)
            throws StripeException {
        return SubscriptionSchedule.create(
                SubscriptionScheduleCreateParams.builder().setFromSubscription(subscriptionId).build(),
                RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
    }

    protected SubscriptionSchedule updateRolloutSchedule(SubscriptionSchedule schedule,
                                                         SubscriptionScheduleUpdateParams params,
                                                         String idempotencyKey) throws StripeException {
        return schedule.update(params, RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
    }

    /**
     * 是否已有 finalize 但未支付的发票。不能用 {@code Invoice.upcoming}：它是续费预览，
     * 任何仍会续费的 active 订阅都会返回，会把整个批次误判成 conflict。
     */
    protected boolean hasUpcomingInvoice(String subscriptionId) {
        try {
            InvoiceCollection invoices = Invoice.list(
                    InvoiceListParams.builder()
                            .setSubscription(subscriptionId)
                            .setStatus(InvoiceListParams.Status.OPEN)
                            .setLimit(10L)
                            .build());
            return invoices != null && invoices.getData() != null && !invoices.getData().isEmpty();
        } catch (StripeException e) {
            throw new BillingDomainException("STRIPE_ERROR",
                    "Open invoice probe failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 账号展示：下周期换价预告（account 响应只读这里）
    // ------------------------------------------------------------------

    /** 已安排且未到生效点的换价行 → 前端「下期将按新价续费」。 */
    public Optional<NextCyclePriceChange> scheduledChangeFor(
            String clerkUserId, String stripeSubscriptionId) {
        if (isBlank(clerkUserId) || isBlank(stripeSubscriptionId)) {
            return Optional.empty();
        }
        List<SubscriptionPriceRolloutEntity> rows = rolloutMapper.selectList(
                new LambdaQueryWrapper<SubscriptionPriceRolloutEntity>()
                        .eq(SubscriptionPriceRolloutEntity::getClerkUserId, clerkUserId)
                        .eq(SubscriptionPriceRolloutEntity::getStatus, STATUS_SCHEDULED));
        SubscriptionPriceRolloutEntity match = null;
        for (SubscriptionPriceRolloutEntity row : rows) {
            if (!stripeSubscriptionId.equals(row.getStripeSubscriptionId())
                    || row.getEffectiveAt() == null
                    || !row.getEffectiveAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
                continue;
            }
            if (match == null || row.getEffectiveAt().isAfter(match.getEffectiveAt())) {
                match = row;
            }
        }
        if (match == null) {
            return Optional.empty();
        }
        final SubscriptionPriceRolloutEntity scheduled = match;
        Integer amountMinor = catalog.bindingById(scheduled.getTargetPriceId())
                .map(binding -> catalog.currencyAmounts(binding).orElse(Map.of())
                        .get(scheduled.getCurrency()))
                .orElse(null);
        return Optional.of(NextCyclePriceChange.builder()
                .amountMinor(amountMinor)
                .currency(scheduled.getCurrency())
                .effectiveAt(scheduled.getEffectiveAt())
                .targetVersion(scheduled.getTargetVersion())
                .build());
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    private void requireStripeConfigured() {
        if (stripeSecretKey == null || stripeSecretKey.isBlank() || "sk_test_xxx".equals(stripeSecretKey)) {
            throw new BillingDomainException("STRIPE_NOT_CONFIGURED", "Stripe secret key is not configured");
        }
    }

    private SubscriptionItem singleItemOrNull(Subscription subscription) {
        if (subscription.getItems() == null || subscription.getItems().getData() == null
                || subscription.getItems().getData().size() != 1) {
            return null;
        }
        return subscription.getItems().getData().get(0);
    }

    private boolean isRealSubscriptionRef(String reference) {
        return hasText(reference) && reference.startsWith("sub_");
    }

    private LocalDateTime fromEpoch(Long value) {
        return value == null ? null : LocalDateTime.ofInstant(Instant.ofEpochSecond(value), ZoneOffset.UTC);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
