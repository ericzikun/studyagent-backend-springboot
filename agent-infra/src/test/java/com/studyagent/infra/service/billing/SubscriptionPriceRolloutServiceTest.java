package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.google.gson.Gson;
import com.studyagent.infra.entity.PricingCountryRouteEntity;
import com.studyagent.infra.entity.PricingZoneEntity;
import com.studyagent.infra.entity.PricingZonePriceEntity;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.StudyPassEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.entity.SubscriptionPriceRolloutEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.PricingCountryRouteMapper;
import com.studyagent.infra.mapper.PricingZoneMapper;
import com.studyagent.infra.mapper.PricingZonePriceMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.StudyPassMapper;
import com.studyagent.infra.mapper.StripePriceAliasMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.SubscriptionPriceRolloutMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper;
import com.studyagent.service.domain.billing.NextCyclePriceChange;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.model.SubscriptionSchedule;
import com.stripe.param.SubscriptionScheduleUpdateParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下周期换价执行器（plan-a-db §9 SubscriptionPriceRolloutServiceTest）：
 * dry-run 分类与回填、apply 快照失效/幂等/用户未来段保护/supersede、verify 三态、
 * cancel 前像恢复、account 下周期预告。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionPriceRolloutServiceTest {

    private static final String ZONE = "Z080";
    private static final String SOURCE_VERSION = "regional-20261018-v1";
    private static final String TARGET_VERSION = "regional-20261115-v2";
    private static final String SOURCE_PRICE = "price_v1_pro";
    private static final String TARGET_PRICE = "price_v2_pro";
    private static final Gson GSON = new Gson();

    @Mock
    private SubscriptionPriceRolloutMapper rolloutMapper;
    @Mock
    private UserSubscriptionMapper userSubscriptionMapper;
    @Mock
    private StudyPassMapper studyPassMapper;
    @Mock
    private SubscriptionPlanMapper subscriptionPlanMapper;
    @Mock
    private RechargeOrderMapper rechargeOrderMapper;
    @Mock
    private PricingZoneMapper zoneMapper;
    @Mock
    private PricingCountryRouteMapper routeMapper;
    @Mock
    private PricingZonePriceMapper priceMapper;
    @Mock
    private StripePriceAliasMapper aliasMapper;

    @BeforeAll
    static void initTableInfo() {
        MybatisPlusTableInfoTestHelper.initTableInfo(SubscriptionPriceRolloutEntity.class);
        MybatisPlusTableInfoTestHelper.initTableInfo(UserSubscriptionEntity.class);
        MybatisPlusTableInfoTestHelper.initTableInfo(StudyPassEntity.class);
        MybatisPlusTableInfoTestHelper.initTableInfo(SubscriptionPlanEntity.class);
        MybatisPlusTableInfoTestHelper.initTableInfo(RechargeOrderEntity.class);
    }

    // ------------------------------------------------------------------
    // dry-run
    // ------------------------------------------------------------------

    @Test
    void dryRunClassifiesPreviewAndWritesLedgerRow() throws Exception {
        long periodEnd = Instant.now().getEpochSecond() + 30L * 24 * 3600;
        loadCatalog();
        UserSubscriptionEntity contract = zoneContract(11L, "user_zone", "sub_zone1");
        when(userSubscriptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(contract));
        when(studyPassMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(rechargeOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(rolloutMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        TestableRolloutService service = service();
        service.subscriptions.put("sub_zone1", subscription("sub_zone1", SOURCE_PRICE, "brl", periodEnd, false));

        SubscriptionPriceRolloutService.DryRunSummary summary = service.dryRun(ZONE, TARGET_VERSION);

        assertEquals(service.buildBatchId(ZONE, TARGET_VERSION), summary.batchId());
        assertEquals(1, summary.scanned());
        assertEquals(1, summary.preview());
        assertEquals(0, summary.conflict());
        assertEquals(0, summary.skipped());
        assertEquals(0, summary.backfilled());
        ArgumentCaptor<SubscriptionPriceRolloutEntity> inserted =
                ArgumentCaptor.forClass(SubscriptionPriceRolloutEntity.class);
        verify(rolloutMapper).insert(inserted.capture());
        SubscriptionPriceRolloutEntity row = inserted.getValue();
        assertEquals("preview", row.getStatus());
        assertEquals("subscription", row.getContractKind());
        assertEquals("brl", row.getCurrency());
        assertEquals(SOURCE_PRICE, row.getSourcePriceId());
        assertEquals(TARGET_PRICE, row.getTargetPriceId());
        assertEquals(TARGET_VERSION, row.getTargetVersion());
        assertEquals(LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneOffset.UTC), row.getEffectiveAt());
        assertEquals("rollout:" + summary.batchId() + ":sub_zone1:" + TARGET_PRICE + ":" + periodEnd,
                row.getIdempotencyKey());
        // zone 列齐备的合同不做回填。
        verify(userSubscriptionMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void dryRunBackfillsLegacyOrderEvidenceContract() throws Exception {
        long periodEnd = Instant.now().getEpochSecond() + 30L * 24 * 3600;
        loadCatalog();
        when(userSubscriptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(studyPassMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        RechargeOrderEntity evidence = new RechargeOrderEntity();
        evidence.setClerkUserId("user_legacy");
        evidence.setCurrency("brl");
        when(rechargeOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(evidence));
        UserSubscriptionEntity legacy = new UserSubscriptionEntity();
        legacy.setId(12L);
        legacy.setClerkUserId("user_legacy");
        legacy.setStripeSubscriptionId("sub_legacy");
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(legacy);
        when(rolloutMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        TestableRolloutService service = service();
        service.subscriptions.put("sub_legacy", subscription("sub_legacy", SOURCE_PRICE, "brl", periodEnd, false));

        SubscriptionPriceRolloutService.DryRunSummary summary = service.dryRun(ZONE, TARGET_VERSION);

        assertEquals(1, summary.preview());
        assertEquals(1, summary.backfilled());
        ArgumentCaptor<Wrapper<UserSubscriptionEntity>> updateWrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(userSubscriptionMapper).update(isNull(), updateWrapper.capture());
        var params = ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserSubscriptionEntity>)
                updateWrapper.getValue()).getParamNameValuePairs();
        assertTrue(params.containsValue(ZONE));
        assertTrue(params.containsValue("legacy"));
        assertTrue(params.containsValue("brl"));
        ArgumentCaptor<SubscriptionPriceRolloutEntity> inserted =
                ArgumentCaptor.forClass(SubscriptionPriceRolloutEntity.class);
        verify(rolloutMapper).insert(inserted.capture());
        assertEquals("legacy", inserted.getValue().getSourceVersion());
    }

    @Test
    void dryRunMarksConflictsForCancellingAndInvoicedContracts() throws Exception {
        long periodEnd = Instant.now().getEpochSecond() + 30L * 24 * 3600;
        loadCatalog();
        when(userSubscriptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                zoneContract(21L, "user_cancel", "sub_cancel"), zoneContract(22L, "user_invoice", "sub_invoiced")));
        when(studyPassMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(rechargeOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(rolloutMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        TestableRolloutService service = service();
        service.subscriptions.put("sub_cancel", subscription("sub_cancel", SOURCE_PRICE, "brl", periodEnd, true));
        service.subscriptions.put("sub_invoiced", subscription("sub_invoiced", SOURCE_PRICE, "brl", periodEnd, false));
        service.upcomingInvoicedSubscriptionIds.add("sub_invoiced");

        SubscriptionPriceRolloutService.DryRunSummary summary = service.dryRun(ZONE, TARGET_VERSION);

        assertEquals(2, summary.conflict());
        assertEquals(0, summary.preview());
        ArgumentCaptor<SubscriptionPriceRolloutEntity> inserted =
                ArgumentCaptor.forClass(SubscriptionPriceRolloutEntity.class);
        verify(rolloutMapper, times(2)).insert(inserted.capture());
        List<String> reasons = inserted.getAllValues().stream()
                .map(SubscriptionPriceRolloutEntity::getStatusReason).toList();
        assertTrue(reasons.contains("cancelling_at_period_end"));
        assertTrue(reasons.contains("already_invoiced_next_period"));
    }

    @Test
    void dryRunSkipsAlreadyOnTargetSameVersionAndUsdContracts() throws Exception {
        long periodEnd = Instant.now().getEpochSecond() + 30L * 24 * 3600;
        loadCatalog();
        UserSubscriptionEntity onTarget = zoneContract(31L, "user_a", "sub_on_target");
        UserSubscriptionEntity sameVersion = zoneContract(32L, "user_b", "sub_same_version");
        sameVersion.setPriceVersion(TARGET_VERSION);
        UserSubscriptionEntity usd = zoneContract(33L, "user_c", "sub_usd");
        when(userSubscriptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(onTarget, sameVersion, usd));
        when(studyPassMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(rechargeOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(rolloutMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        TestableRolloutService service = service();
        service.subscriptions.put("sub_on_target", subscription("sub_on_target", TARGET_PRICE, "brl", periodEnd, false));
        service.subscriptions.put("sub_same_version", subscription("sub_same_version", SOURCE_PRICE, "brl", periodEnd, false));
        service.subscriptions.put("sub_usd", subscription("sub_usd", SOURCE_PRICE, "usd", periodEnd, false));

        SubscriptionPriceRolloutService.DryRunSummary summary = service.dryRun(ZONE, TARGET_VERSION);

        assertEquals(3, summary.skipped());
        assertEquals(0, summary.preview());
        ArgumentCaptor<SubscriptionPriceRolloutEntity> inserted =
                ArgumentCaptor.forClass(SubscriptionPriceRolloutEntity.class);
        verify(rolloutMapper, times(3)).insert(inserted.capture());
        List<String> reasons = inserted.getAllValues().stream()
                .map(SubscriptionPriceRolloutEntity::getStatusReason).toList();
        assertTrue(reasons.contains("already_on_target"));
        assertTrue(reasons.contains("same_version"));
        assertTrue(reasons.contains("usd_contract_no_target"));
    }

    @Test
    void dryRunSkipsWhenTargetBindingMissingOrNotBackfilled() throws Exception {
        long periodEnd = Instant.now().getEpochSecond() + 30L * 24 * 3600;
        // v2 版本缺 pro_monthly 绑定、basic_monthly 未回填 Price。
        PricingZonePriceEntity draft = binding(ZONE, TARGET_VERSION, "basic_monthly", null, "{\"brl\":{\"amountMinor\":999}}");
        PricingZonePriceEntity oldVersion = binding(ZONE, SOURCE_VERSION, "pro_monthly", SOURCE_PRICE, "{\"brl\":{\"amountMinor\":3999}}");
        when(zoneMapper.selectList(any())).thenReturn(List.of(zone(true)));
        when(routeMapper.selectList(any())).thenReturn(List.of());
        when(priceMapper.selectList(any())).thenReturn(List.of(draft, oldVersion));
        when(aliasMapper.selectList(any())).thenReturn(List.of());
        when(userSubscriptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                zoneContract(41L, "user_d", "sub_no_binding"), zoneContract(42L, "user_e", "sub_draft")));
        when(studyPassMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(rechargeOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        SubscriptionPlanEntity basicPlan = new SubscriptionPlanEntity();
        basicPlan.setPlanCode("basic_monthly");
        basicPlan.setStripePriceId("price_v1_basic");
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(basicPlan);
        when(rolloutMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        TestableRolloutService service = service();
        service.subscriptions.put("sub_no_binding", subscription("sub_no_binding", SOURCE_PRICE, "brl", periodEnd, false));
        service.subscriptions.put("sub_draft", subscription("sub_draft", "price_v1_basic", "brl", periodEnd, false));

        SubscriptionPriceRolloutService.DryRunSummary summary = service.dryRun(ZONE, TARGET_VERSION);

        assertEquals(2, summary.skipped());
        ArgumentCaptor<SubscriptionPriceRolloutEntity> inserted =
                ArgumentCaptor.forClass(SubscriptionPriceRolloutEntity.class);
        verify(rolloutMapper, times(2)).insert(inserted.capture());
        List<String> reasons = inserted.getAllValues().stream()
                .map(SubscriptionPriceRolloutEntity::getStatusReason).toList();
        assertTrue(reasons.stream().anyMatch(r -> r.startsWith("no_target_binding")));
        assertTrue(reasons.contains("target_price_not_backfilled"));
    }

    @Test
    void dryRunNoopsContractsAlreadyInTerminalLedgerState() throws Exception {
        loadCatalog();
        when(userSubscriptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(zoneContract(51L, "user_f", "sub_done")));
        when(studyPassMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(rechargeOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(rolloutMapper.selectOne(any(Wrapper.class))).thenReturn(ledgerRow("rollout-batch", "sub_done",
                SOURCE_PRICE, TARGET_PRICE, "scheduled", LocalDateTime.now(ZoneOffset.UTC).plusDays(3)));
        TestableRolloutService service = service();

        SubscriptionPriceRolloutService.DryRunSummary summary = service.dryRun(ZONE, TARGET_VERSION);

        assertEquals(1, summary.noop());
        verify(rolloutMapper, never()).insert(any(SubscriptionPriceRolloutEntity.class));
        verify(rolloutMapper, never()).updateById(any(SubscriptionPriceRolloutEntity.class));
    }

    // ------------------------------------------------------------------
    // apply
    // ------------------------------------------------------------------

    @Test
    void applyMergesScheduleAtPeriodEndAndStoresPreimage() throws Exception {
        long periodStart = Instant.now().getEpochSecond();
        long periodEnd = periodStart + 30L * 24 * 3600;
        String batchId = "rollout-" + ZONE + "-20261115-v2-20261018";
        SubscriptionPriceRolloutEntity row = ledgerRow(batchId, "sub_zone1", SOURCE_PRICE, TARGET_PRICE,
                "preview", LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneOffset.UTC));
        when(rolloutMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row), List.of());
        TestableRolloutService service = service();
        Subscription live = subscription("sub_zone1", SOURCE_PRICE, "brl", periodEnd, false);
        live.setCurrentPeriodStart(periodStart);
        live.setSchedule("sub_sched_1");
        service.subscriptions.put("sub_zone1", live);
        service.schedules.put("sub_sched_1", schedule("sub_sched_1", Map.of("keep_me", "yes"),
                new SubscriptionPriceRolloutService.PhaseSpec(periodStart, periodEnd, SOURCE_PRICE, 1L)));

        SubscriptionPriceRolloutService.ApplySummary summary = service.apply(batchId);

        assertEquals(1, summary.scheduled());
        assertEquals(1, service.updateParams.size());
        SubscriptionScheduleUpdateParams params = service.updateParams.get(0);
        var phases = params.getPhases();
        assertEquals(2, phases.size());
        assertEquals(SOURCE_PRICE, phases.get(0).getItems().get(0).getPrice());
        assertEquals(periodStart, (long) phases.get(0).getStartDate());
        assertEquals(periodEnd, (long) phases.get(0).getEndDate());
        assertEquals(TARGET_PRICE, phases.get(1).getItems().get(0).getPrice());
        assertEquals(periodEnd, (long) phases.get(1).getStartDate());
        assertNull(phases.get(1).getEndDate());
        assertEquals("brl", phases.get(1).getCurrency());
        @SuppressWarnings("unchecked")
        Map<String, String> writeMetadata = (Map<String, String>) params.getMetadata();
        assertEquals("zone_price_rollout", writeMetadata.get("change_type"));
        assertEquals(batchId, writeMetadata.get("rollout_batch"));
        assertEquals("yes", writeMetadata.get("keep_me"));
        assertEquals("rollout:" + batchId + ":sub_zone1:" + TARGET_PRICE + ":" + periodEnd,
                service.updateIdempotencyKeys.get(0));
        ArgumentCaptor<SubscriptionPriceRolloutEntity> updated =
                ArgumentCaptor.forClass(SubscriptionPriceRolloutEntity.class);
        verify(rolloutMapper).updateById(updated.capture());
        assertEquals("scheduled", updated.getValue().getStatus());
        assertTrue(updated.getValue().getSnapshotJson().contains("preimage_phases"));
        assertTrue(updated.getValue().getSnapshotJson().contains(SOURCE_PRICE));
    }

    @Test
    void applyConflictsWhenLivePriceDriftedFromSnapshot() throws Exception {
        long periodEnd = Instant.now().getEpochSecond() + 30L * 24 * 3600;
        String batchId = "rollout-" + ZONE + "-20261115-v2-20261018";
        SubscriptionPriceRolloutEntity row = ledgerRow(batchId, "sub_drift", SOURCE_PRICE, TARGET_PRICE,
                "preview", LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneOffset.UTC));
        when(rolloutMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row), List.of());
        TestableRolloutService service = service();
        service.subscriptions.put("sub_drift", subscription("sub_drift", "price_elsewhere", "brl", periodEnd, false));

        SubscriptionPriceRolloutService.ApplySummary summary = service.apply(batchId);

        assertEquals(1, summary.conflict());
        assertTrue(service.updateParams.isEmpty());
        ArgumentCaptor<SubscriptionPriceRolloutEntity> updated =
                ArgumentCaptor.forClass(SubscriptionPriceRolloutEntity.class);
        verify(rolloutMapper).updateById(updated.capture());
        assertEquals("snapshot_stale:price_changed", updated.getValue().getStatusReason());
    }

    @Test
    void applyNoopsWhenTargetPhaseAlreadyWrittenByRetry() throws Exception {
        long periodStart = Instant.now().getEpochSecond();
        long periodEnd = periodStart + 30L * 24 * 3600;
        String batchId = "rollout-" + ZONE + "-20261115-v2-20261018";
        SubscriptionPriceRolloutEntity row = ledgerRow(batchId, "sub_retry", SOURCE_PRICE, TARGET_PRICE,
                "preview", LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneOffset.UTC));
        when(rolloutMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row), List.of());
        TestableRolloutService service = service();
        Subscription live = subscription("sub_retry", SOURCE_PRICE, "brl", periodEnd, false);
        live.setSchedule("sub_sched_retry");
        service.subscriptions.put("sub_retry", live);
        service.schedules.put("sub_sched_retry", schedule("sub_sched_retry", Map.of(),
                new SubscriptionPriceRolloutService.PhaseSpec(periodStart, periodEnd, SOURCE_PRICE, 1L),
                new SubscriptionPriceRolloutService.PhaseSpec(periodEnd, null, TARGET_PRICE, 1L)));

        SubscriptionPriceRolloutService.ApplySummary summary = service.apply(batchId);

        assertEquals(1, summary.noop());
        assertTrue(service.updateParams.isEmpty());
        ArgumentCaptor<SubscriptionPriceRolloutEntity> updated =
                ArgumentCaptor.forClass(SubscriptionPriceRolloutEntity.class);
        verify(rolloutMapper).updateById(updated.capture());
        assertEquals("scheduled", updated.getValue().getStatus());
        assertEquals("already_written", updated.getValue().getStatusReason());
    }

    @Test
    void applyRefusesToOverwriteUserPendingFutureChange() throws Exception {
        long periodStart = Instant.now().getEpochSecond();
        long periodEnd = periodStart + 30L * 24 * 3600;
        String batchId = "rollout-" + ZONE + "-20261115-v2-20261018";
        SubscriptionPriceRolloutEntity row = ledgerRow(batchId, "sub_user_change", SOURCE_PRICE, TARGET_PRICE,
                "preview", LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneOffset.UTC));
        when(rolloutMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row), List.of());
        TestableRolloutService service = service();
        Subscription live = subscription("sub_user_change", SOURCE_PRICE, "brl", periodEnd, false);
        live.setSchedule("sub_sched_user");
        service.subscriptions.put("sub_user_change", live);
        // 用户自己预约的降级段：非本批次目标价，且 Schedule 无 rollout 元数据 → 必须冲突。
        service.schedules.put("sub_sched_user", schedule("sub_sched_user", Map.of(),
                new SubscriptionPriceRolloutService.PhaseSpec(periodStart, periodEnd, SOURCE_PRICE, 1L),
                new SubscriptionPriceRolloutService.PhaseSpec(periodEnd, periodEnd + 30L * 24 * 3600, "price_user_downgrade", 1L)));

        SubscriptionPriceRolloutService.ApplySummary summary = service.apply(batchId);

        assertEquals(1, summary.conflict());
        assertTrue(service.updateParams.isEmpty());
        ArgumentCaptor<SubscriptionPriceRolloutEntity> updated =
                ArgumentCaptor.forClass(SubscriptionPriceRolloutEntity.class);
        verify(rolloutMapper).updateById(updated.capture());
        assertEquals("user_future_change_pending", updated.getValue().getStatusReason());
    }

    @Test
    void applyReplacesPriorRolloutPhaseWhenNewerBatchSupersedes() throws Exception {
        long periodStart = Instant.now().getEpochSecond();
        long periodEnd = periodStart + 30L * 24 * 3600;
        String priorBatch = "rollout-" + ZONE + "-20261115-v2-20261018";
        String newBatch = "rollout-" + ZONE + "-20270115-v3-20261018";
        SubscriptionPriceRolloutEntity priorRow = ledgerRow(priorBatch, "sub_zone1", SOURCE_PRICE, TARGET_PRICE,
                "scheduled", LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneOffset.UTC));
        SubscriptionPriceRolloutEntity newRow = ledgerRow(newBatch, "sub_zone1", SOURCE_PRICE, "price_v3_pro",
                "preview", LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneOffset.UTC));
        when(rolloutMapper.selectList(any(Wrapper.class))).thenReturn(List.of(newRow), List.of(priorRow));
        when(rolloutMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        TestableRolloutService service = service();
        Subscription live = subscription("sub_zone1", SOURCE_PRICE, "brl", periodEnd, false);
        live.setSchedule("sub_sched_prior");
        service.subscriptions.put("sub_zone1", live);
        // 前一批 rollout 已写入的目标段：批次元数据 + 台账可证明 → 新批次允许改价。
        service.schedules.put("sub_sched_prior", schedule("sub_sched_prior",
                Map.of("change_type", "zone_price_rollout", "rollout_batch", priorBatch),
                new SubscriptionPriceRolloutService.PhaseSpec(periodStart, periodEnd, SOURCE_PRICE, 1L),
                new SubscriptionPriceRolloutService.PhaseSpec(periodEnd, null, TARGET_PRICE, 1L)));

        SubscriptionPriceRolloutService.ApplySummary summary = service.apply(newBatch);

        assertEquals(1, summary.superseded());
        assertEquals(1, service.updateParams.size());
        var phases = service.updateParams.get(0).getPhases();
        assertEquals(2, phases.size());
        assertEquals("price_v3_pro", phases.get(1).getItems().get(0).getPrice());
        ArgumentCaptor<SubscriptionPriceRolloutEntity> updated =
                ArgumentCaptor.forClass(SubscriptionPriceRolloutEntity.class);
        verify(rolloutMapper, times(2)).updateById(updated.capture());
        assertEquals("superseded", updated.getAllValues().get(0).getStatus());
        assertEquals("superseded_by:" + newBatch, updated.getAllValues().get(0).getStatusReason());
        assertEquals("scheduled", updated.getAllValues().get(1).getStatus());
    }

    @Test
    void applyConflictsWhenPeriodDriftedFromSnapshot() throws Exception {
        long periodEnd = Instant.now().getEpochSecond() + 30L * 24 * 3600;
        String batchId = "rollout-" + ZONE + "-20261115-v2-20261018";
        SubscriptionPriceRolloutEntity row = ledgerRow(batchId, "sub_period", SOURCE_PRICE, TARGET_PRICE,
                "preview", LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd - 3600), ZoneOffset.UTC));
        when(rolloutMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row), List.of());
        TestableRolloutService service = service();
        service.subscriptions.put("sub_period", subscription("sub_period", SOURCE_PRICE, "brl", periodEnd, false));

        SubscriptionPriceRolloutService.ApplySummary summary = service.apply(batchId);

        assertEquals(1, summary.conflict());
        assertTrue(service.updateParams.isEmpty());
        ArgumentCaptor<SubscriptionPriceRolloutEntity> updated =
                ArgumentCaptor.forClass(SubscriptionPriceRolloutEntity.class);
        verify(rolloutMapper).updateById(updated.capture());
        assertEquals("snapshot_stale:period_changed", updated.getValue().getStatusReason());
    }

    // ------------------------------------------------------------------
    // verify
    // ------------------------------------------------------------------

    @Test
    void verifyAdvancesAppliedKeepsScheduledAndFailsMissingSchedule() throws Exception {
        long periodEnd = Instant.now().getEpochSecond() + 30L * 24 * 3600;
        String batchId = "rollout-" + ZONE + "-20261115-v2-20261018";
        SubscriptionPriceRolloutEntity appliedRow = ledgerRow(batchId, "sub_applied", SOURCE_PRICE, TARGET_PRICE,
                "scheduled", LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneOffset.UTC));
        SubscriptionPriceRolloutEntity pendingRow = ledgerRow(batchId, "sub_pending", SOURCE_PRICE, TARGET_PRICE,
                "scheduled", LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneOffset.UTC));
        SubscriptionPriceRolloutEntity missingRow = ledgerRow(batchId, "sub_missing", SOURCE_PRICE, TARGET_PRICE,
                "scheduled", LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneOffset.UTC));
        when(rolloutMapper.selectList(any(Wrapper.class))).thenReturn(List.of(appliedRow, pendingRow, missingRow));
        TestableRolloutService service = service();
        service.subscriptions.put("sub_applied", subscription("sub_applied", TARGET_PRICE, "brl", periodEnd, false));
        Subscription pending = subscription("sub_pending", SOURCE_PRICE, "brl", periodEnd, false);
        pending.setSchedule("sub_sched_pending");
        service.subscriptions.put("sub_pending", pending);
        service.schedules.put("sub_sched_pending", schedule("sub_sched_pending", Map.of(),
                new SubscriptionPriceRolloutService.PhaseSpec(periodEnd - 30L * 24 * 3600, periodEnd, SOURCE_PRICE, 1L),
                new SubscriptionPriceRolloutService.PhaseSpec(periodEnd, null, TARGET_PRICE, 1L)));
        service.subscriptions.put("sub_missing", subscription("sub_missing", SOURCE_PRICE, "brl", periodEnd, false));

        SubscriptionPriceRolloutService.VerifySummary summary = service.verify(batchId);

        assertEquals(1, summary.applied());
        assertEquals(1, summary.scheduled());
        assertEquals(1, summary.failed());
        ArgumentCaptor<SubscriptionPriceRolloutEntity> updated =
                ArgumentCaptor.forClass(SubscriptionPriceRolloutEntity.class);
        verify(rolloutMapper, times(2)).updateById(updated.capture());
        assertEquals("applied", updated.getAllValues().get(0).getStatus());
        assertEquals("phase_effective", updated.getAllValues().get(0).getStatusReason());
        assertEquals("failed", updated.getAllValues().get(1).getStatus());
        assertEquals("schedule_missing", updated.getAllValues().get(1).getStatusReason());
    }

    // ------------------------------------------------------------------
    // cancel
    // ------------------------------------------------------------------

    @Test
    void cancelRestoresPreimageAndStripsRolloutMetadata() throws Exception {
        long periodStart = Instant.now().getEpochSecond();
        long periodEnd = periodStart + 30L * 24 * 3600;
        String batchId = "rollout-" + ZONE + "-20261115-v2-20261018";
        Map<String, Object> preimage = new LinkedHashMap<>();
        preimage.put("schedule_id", "sub_sched_1");
        preimage.put("preimage_phases", List.of(Map.of(
                "start", periodStart, "end", periodEnd, "priceId", SOURCE_PRICE, "quantity", 1)));
        preimage.put("replaced_phase_index", -1);
        SubscriptionPriceRolloutEntity row = ledgerRow(batchId, "sub_zone1", SOURCE_PRICE, TARGET_PRICE,
                "scheduled", LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneOffset.UTC));
        row.setSnapshotJson(GSON.toJson(preimage));
        when(rolloutMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        TestableRolloutService service = service();
        service.schedules.put("sub_sched_1", schedule("sub_sched_1",
                Map.of("change_type", "zone_price_rollout", "rollout_batch", batchId, "clerk_user_id", "user_sub_zone1"),
                new SubscriptionPriceRolloutService.PhaseSpec(periodEnd, null, TARGET_PRICE, 1L)));

        SubscriptionPriceRolloutService.CancelSummary summary = service.cancel(batchId);

        assertEquals(1, summary.cancelled());
        assertEquals(1, service.updateParams.size());
        SubscriptionScheduleUpdateParams params = service.updateParams.get(0);
        assertEquals(1, params.getPhases().size());
        assertEquals(SOURCE_PRICE, params.getPhases().get(0).getItems().get(0).getPrice());
        assertEquals(periodStart, (long) params.getPhases().get(0).getStartDate());
        assertEquals(periodEnd, (long) params.getPhases().get(0).getEndDate());
        @SuppressWarnings("unchecked")
        Map<String, String> writeMetadata = (Map<String, String>) params.getMetadata();
        assertFalse(writeMetadata.containsKey("rollout_batch"));
        assertEquals("zone_price_rollout_cancelled", writeMetadata.get("change_type"));
        assertEquals("rollout-cancel:" + batchId + ":sub_zone1", service.updateIdempotencyKeys.get(0));
        assertEquals("cancelled", row.getStatus());
    }

    @Test
    void cancelMarksUnwrittenRowsCancelledWithoutStripeAccess() throws Exception {
        String batchId = "rollout-" + ZONE + "-20261115-v2-20261018";
        SubscriptionPriceRolloutEntity row = ledgerRow(batchId, "sub_preview", SOURCE_PRICE, TARGET_PRICE,
                "preview", LocalDateTime.now(ZoneOffset.UTC).plusDays(3));
        when(rolloutMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        TestableRolloutService service = service();

        SubscriptionPriceRolloutService.CancelSummary summary = service.cancel(batchId);

        assertEquals(1, summary.cancelled());
        assertTrue(service.updateParams.isEmpty());
        assertEquals("cancelled", row.getStatus());
        assertEquals("never_written", row.getStatusReason());
    }

    @Test
    void cancelFailsWhenPreimageSnapshotIsMissing() throws Exception {
        String batchId = "rollout-" + ZONE + "-20261115-v2-20261018";
        SubscriptionPriceRolloutEntity row = ledgerRow(batchId, "sub_no_preimage", SOURCE_PRICE, TARGET_PRICE,
                "scheduled", LocalDateTime.now(ZoneOffset.UTC).plusDays(3));
        row.setSnapshotJson("{\"schedule_id\":\"sub_sched_1\"}");
        when(rolloutMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        TestableRolloutService service = service();

        SubscriptionPriceRolloutService.CancelSummary summary = service.cancel(batchId);

        assertEquals(1, summary.failed());
        assertTrue(service.updateParams.isEmpty());
        assertEquals("failed", row.getStatus());
        assertEquals("preimage_missing", row.getStatusReason());
    }

    // ------------------------------------------------------------------
    // account 下周期预告
    // ------------------------------------------------------------------

    @Test
    void scheduledChangeForReturnsRegistryAmountForFutureScheduledRow() {
        loadCatalog();
        SubscriptionPriceRolloutEntity future = ledgerRow("rollout-" + ZONE + "-20261115-v2-20261018", "sub_zone1",
                SOURCE_PRICE, TARGET_PRICE, "scheduled", LocalDateTime.now(ZoneOffset.UTC).plusDays(10));
        when(rolloutMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                ledgerRow("rollout-old", "sub_zone1", SOURCE_PRICE, TARGET_PRICE, "scheduled",
                        LocalDateTime.now(ZoneOffset.UTC).minusDays(1)),
                ledgerRow("rollout-other", "sub_other", SOURCE_PRICE, TARGET_PRICE, "scheduled",
                        LocalDateTime.now(ZoneOffset.UTC).plusDays(5)),
                future));
        TestableRolloutService service = service();

        Optional<NextCyclePriceChange> change = service.scheduledChangeFor("user_zone", "sub_zone1");

        assertTrue(change.isPresent());
        assertEquals(4499, change.get().getAmountMinor());
        assertEquals("brl", change.get().getCurrency());
        assertEquals(future.getEffectiveAt(), change.get().getEffectiveAt());
        assertEquals(TARGET_VERSION, change.get().getTargetVersion());
    }

    @Test
    void scheduledChangeForEmptyWithoutMatchingSubscription() {
        TestableRolloutService service = service();
        assertTrue(service.scheduledChangeFor("user_zone", "sub_zone1").isEmpty());
        assertTrue(service.scheduledChangeFor(null, "sub_zone1").isEmpty());
        assertTrue(service.scheduledChangeFor("user_zone", null).isEmpty());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private TestableRolloutService service() {
        ZonePricingCatalog catalog = new ZonePricingCatalog(zoneMapper, routeMapper, priceMapper, aliasMapper, 60);
        ZonePricingSelectionService zonePricing = new ZonePricingSelectionService(catalog,
                new BillingPricingService(
                        org.mockito.Mockito.mock(StripePriceGateway.class),
                        new BillingRegionalPricingProperties()));
        TestableRolloutService service = new TestableRolloutService(rolloutMapper, userSubscriptionMapper, studyPassMapper,
                subscriptionPlanMapper, rechargeOrderMapper, catalog, zonePricing);
        try {
            var field = SubscriptionPriceRolloutService.class.getDeclaredField("stripeSecretKey");
            field.setAccessible(true);
            field.set(service, "sk_test_123");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return service;
    }

    /** v1=v2 两版 pro_monthly 绑定 + BR 路由：v1 3999 brl、v2 4499 brl。 */
    private void loadCatalog() {
        when(zoneMapper.selectList(any())).thenReturn(List.of(zone(true)));
        PricingCountryRouteEntity brRoute = new PricingCountryRouteEntity();
        brRoute.setCountryCode("BR");
        brRoute.setZoneCode(ZONE);
        brRoute.setCurrency("brl");
        brRoute.setLabel("Brazil");
        brRoute.setIsEnabled(true);
        when(routeMapper.selectList(any())).thenReturn(List.of(brRoute));
        when(priceMapper.selectList(any())).thenReturn(List.of(
                binding(ZONE, SOURCE_VERSION, "pro_monthly", SOURCE_PRICE, "{\"brl\":{\"amountMinor\":3999}}"),
                binding(ZONE, TARGET_VERSION, "pro_monthly", TARGET_PRICE, "{\"brl\":{\"amountMinor\":4499}}")));
        when(aliasMapper.selectList(any())).thenReturn(List.of());
    }

    private static PricingZoneEntity zone(boolean active) {
        PricingZoneEntity entity = new PricingZoneEntity();
        entity.setZoneCode(ZONE);
        entity.setLabel("测试区");
        entity.setActiveVersion(SOURCE_VERSION);
        entity.setIsActive(active);
        return entity;
    }

    private static PricingZonePriceEntity binding(String zoneCode, String version, String sku,
                                                  String priceId, String amountsJson) {
        PricingZonePriceEntity entity = new PricingZonePriceEntity();
        entity.setZoneCode(zoneCode);
        entity.setPriceVersion(version);
        entity.setSkuCode(sku);
        entity.setSkuKind("subscription_plan");
        entity.setStripePriceId(priceId);
        entity.setStripeProductId("prod_" + sku);
        entity.setPriceType("recurring");
        entity.setBillingInterval("month");
        entity.setIntervalCount(1);
        entity.setUsdDefaultAmountMinor(7999);
        entity.setCurrencyAmounts(amountsJson);
        entity.setIsActive(true);
        return entity;
    }

    private static UserSubscriptionEntity zoneContract(Long id, String user, String subscriptionId) {
        UserSubscriptionEntity entity = new UserSubscriptionEntity();
        entity.setId(id);
        entity.setClerkUserId(user);
        entity.setStripeSubscriptionId(subscriptionId);
        entity.setPricingZone(ZONE);
        entity.setPriceVersion(SOURCE_VERSION);
        entity.setCurrency("brl");
        entity.setStatus("active");
        return entity;
    }

    private static SubscriptionPriceRolloutEntity ledgerRow(String batchId, String subscriptionId,
                                                            String sourcePriceId, String targetPriceId,
                                                            String status, LocalDateTime effectiveAt) {
        SubscriptionPriceRolloutEntity row = new SubscriptionPriceRolloutEntity();
        row.setId(Long.valueOf((subscriptionId + status).hashCode()));
        row.setBatchId(batchId);
        row.setContractKind("subscription");
        row.setClerkUserId("user_" + subscriptionId.replace("sub_", "sub_"));
        row.setStripeSubscriptionId(subscriptionId);
        row.setZoneCode(ZONE);
        row.setCurrency("brl");
        row.setSourcePriceId(sourcePriceId);
        row.setSourceVersion(SOURCE_VERSION);
        row.setTargetPriceId(targetPriceId);
        row.setTargetVersion(TARGET_VERSION);
        row.setEffectiveAt(effectiveAt);
        row.setStatus(status);
        return row;
    }

    private static Subscription subscription(String id, String priceId, String currency,
                                             long periodEnd, boolean cancelling) {
        Subscription subscription = new Subscription();
        subscription.setId(id);
        subscription.setStatus("active");
        subscription.setCurrency(currency);
        subscription.setCurrentPeriodStart(periodEnd - 30L * 24 * 3600);
        subscription.setCurrentPeriodEnd(periodEnd);
        subscription.setCancelAtPeriodEnd(cancelling);
        SubscriptionItem item = new SubscriptionItem();
        Price price = new Price();
        price.setId(priceId);
        item.setPrice(price);
        item.setQuantity(1L);
        SubscriptionItemCollection items = new SubscriptionItemCollection();
        items.setData(List.of(item));
        subscription.setItems(items);
        return subscription;
    }

    private static SubscriptionSchedule schedule(String id, Map<String, String> metadata,
                                                 SubscriptionPriceRolloutService.PhaseSpec... phases) {
        SubscriptionSchedule schedule = new SubscriptionSchedule();
        schedule.setId(id);
        schedule.setStatus("active");
        schedule.setMetadata(metadata);
        List<SubscriptionSchedule.Phase> phaseList = new ArrayList<>();
        for (SubscriptionPriceRolloutService.PhaseSpec spec : phases) {
            SubscriptionSchedule.Phase phase = new SubscriptionSchedule.Phase();
            phase.setStartDate(spec.start());
            phase.setEndDate(spec.end());
            SubscriptionSchedule.Phase.Item item = new SubscriptionSchedule.Phase.Item();
            item.setPrice(spec.priceId());
            item.setQuantity(spec.quantity());
            phase.setItems(List.of(item));
            phaseList.add(phase);
        }
        schedule.setPhases(phaseList);
        return schedule;
    }

    /** Stripe 访问覆写：不触网，逐 id 回放预置对象并记录写入参数。 */
    private static class TestableRolloutService extends SubscriptionPriceRolloutService {
        final Map<String, Subscription> subscriptions = new LinkedHashMap<>();
        final Map<String, SubscriptionSchedule> schedules = new LinkedHashMap<>();
        final List<SubscriptionScheduleUpdateParams> updateParams = new ArrayList<>();
        final List<String> updateIdempotencyKeys = new ArrayList<>();
        final List<String> upcomingInvoicedSubscriptionIds = new ArrayList<>();

        TestableRolloutService(SubscriptionPriceRolloutMapper rolloutMapper,
                               UserSubscriptionMapper userSubscriptionMapper,
                               StudyPassMapper studyPassMapper,
                               SubscriptionPlanMapper subscriptionPlanMapper,
                               RechargeOrderMapper rechargeOrderMapper,
                               ZonePricingCatalog catalog,
                               ZonePricingSelectionService zonePricing) {
            super(rolloutMapper, userSubscriptionMapper, studyPassMapper, subscriptionPlanMapper,
                    rechargeOrderMapper, catalog, zonePricing);
        }

        @Override
        protected Subscription retrieveRolloutSubscription(String subscriptionId) {
            return subscriptions.get(subscriptionId);
        }

        @Override
        protected SubscriptionSchedule retrieveRolloutScheduleOrNull(String scheduleId) {
            SubscriptionSchedule schedule = schedules.get(scheduleId);
            return schedule == null || "canceled".equals(schedule.getStatus()) ? null : schedule;
        }

        @Override
        protected SubscriptionSchedule createRolloutSchedule(String subscriptionId, String idempotencyKey) {
            SubscriptionSchedule created = new SubscriptionSchedule();
            created.setId("sub_sched_created_" + subscriptionId);
            created.setStatus("active");
            schedules.put(created.getId(), created);
            return created;
        }

        @Override
        protected SubscriptionSchedule updateRolloutSchedule(SubscriptionSchedule schedule,
                                                             SubscriptionScheduleUpdateParams params,
                                                             String idempotencyKey) {
            updateParams.add(params);
            updateIdempotencyKeys.add(idempotencyKey);
            SubscriptionSchedule updated = new SubscriptionSchedule();
            updated.setId(schedule.getId());
            return updated;
        }

        @Override
        protected boolean hasUpcomingInvoice(String subscriptionId) {
            return upcomingInvoicedSubscriptionIds.contains(subscriptionId);
        }
    }
}
