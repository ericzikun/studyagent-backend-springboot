package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.studyagent.infra.entity.AiFeatureDefsEntity;
import com.studyagent.infra.entity.QuotaLedgerAllocationEntity;
import com.studyagent.infra.entity.QuotaLedgerEntity;
import com.studyagent.infra.entity.UserAddonGrantEntity;
import com.studyagent.infra.entity.UserAiQuotaEntity;
import com.studyagent.infra.mapper.AiFeatureDefsMapper;
import com.studyagent.infra.mapper.AiFeaturePackageMapper;
import com.studyagent.infra.mapper.QuotaLedgerAllocationMapper;
import com.studyagent.infra.mapper.QuotaLedgerMapper;
import com.studyagent.infra.mapper.UserAddonGrantMapper;
import com.studyagent.infra.mapper.UserAiQuotaMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.quota.ConsumeResult;
import com.studyagent.service.domain.quota.PlanQuotaService;
import com.studyagent.service.domain.quota.QuotaBalance;
import com.studyagent.service.domain.quota.QuotaLedgerPageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotaDomainServiceImplTest {
    @Mock
    private AiFeatureDefsMapper aiFeatureDefsMapper;
    @Mock
    private AiFeaturePackageMapper aiFeaturePackageMapper;
    @Mock
    private UserAiQuotaMapper userAiQuotaMapper;
    @Mock
    private QuotaLedgerMapper quotaLedgerMapper;
    @Mock
    private UserAddonGrantMapper userAddonGrantMapper;
    @Mock
    private QuotaLedgerAllocationMapper quotaLedgerAllocationMapper;
    @Mock
    private PlanQuotaService planQuotaService;
    @Mock
    private UserSubscriptionMapper userSubscriptionMapper;

    @Test
    void getLedgerPage_filtersAddonLifecycleRowsFromUsageHistory() {
        AiFeatureDefsEntity featureDef = assignmentFeature();

        QuotaLedgerEntity visible = new QuotaLedgerEntity();
        visible.setId(1L);
        visible.setClerkUserId("user_1");
        visible.setFeatureCode("task_create");
        visible.setLedgerType("consume");
        visible.setAmount(-1L);
        visible.setSourceType("verla_session");
        visible.setSourceId("session_1");
        visible.setCreatedAt(LocalDateTime.parse("2026-06-26T10:00:00"));

        QuotaLedgerEntity hidden = new QuotaLedgerEntity();
        hidden.setId(2L);
        hidden.setClerkUserId("user_1");
        hidden.setFeatureCode("addon");
        hidden.setLedgerType("addon_resume");
        hidden.setAmount(0L);
        hidden.setSourceType("subscription");
        hidden.setSourceId("sub_1");
        hidden.setCreatedAt(LocalDateTime.parse("2026-06-26T09:00:00"));

        Page<QuotaLedgerEntity> page = new Page<>(1, 20, 2);
        page.setRecords(List.of(visible, hidden));

        when(quotaLedgerMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        when(quotaLedgerAllocationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        QuotaDomainServiceImpl service = service();

        QuotaLedgerPageResult result = service.getLedgerPage("user_1", null, 1, 20);

        assertEquals(1, result.items().size());
        assertEquals("consume", result.items().get(0).ledgerType());
    }

    @Test
    void getLedgerPage_filtersInternalGrantRowsFromUsageHistory() {
        AiFeatureDefsEntity featureDef = assignmentFeature();

        QuotaLedgerEntity visible = new QuotaLedgerEntity();
        visible.setId(11L);
        visible.setClerkUserId("user_1");
        visible.setFeatureCode("task_create");
        visible.setLedgerType("recharge");
        visible.setAmount(3L);
        visible.setSourceType("checkout");
        visible.setSourceId("cs_1");
        visible.setCreatedAt(LocalDateTime.parse("2026-06-26T10:00:00"));

        QuotaLedgerEntity hiddenCompensation = new QuotaLedgerEntity();
        hiddenCompensation.setId(12L);
        hiddenCompensation.setClerkUserId("user_1");
        hiddenCompensation.setFeatureCode("task_create");
        hiddenCompensation.setLedgerType("compensation_grant");
        hiddenCompensation.setAmount(1L);
        hiddenCompensation.setSourceType("system");
        hiddenCompensation.setSourceId("task_create");
        hiddenCompensation.setCreatedAt(LocalDateTime.parse("2026-06-26T09:30:00"));

        QuotaLedgerEntity hiddenMigration = new QuotaLedgerEntity();
        hiddenMigration.setId(13L);
        hiddenMigration.setClerkUserId("user_1");
        hiddenMigration.setFeatureCode("task_create");
        hiddenMigration.setLedgerType("legacy_migration_grant");
        hiddenMigration.setAmount(10000L);
        hiddenMigration.setSourceType("system");
        hiddenMigration.setSourceId("task_create");
        hiddenMigration.setCreatedAt(LocalDateTime.parse("2026-06-26T09:00:00"));

        QuotaLedgerEntity hiddenMigrationRefund = new QuotaLedgerEntity();
        hiddenMigrationRefund.setId(14L);
        hiddenMigrationRefund.setClerkUserId("user_1");
        hiddenMigrationRefund.setFeatureCode("task_create");
        hiddenMigrationRefund.setLedgerType("legacy_migration_refund_grant");
        hiddenMigrationRefund.setAmount(10000L);
        hiddenMigrationRefund.setSourceType("system");
        hiddenMigrationRefund.setSourceId("task_create");
        hiddenMigrationRefund.setCreatedAt(LocalDateTime.parse("2026-06-26T08:30:00"));

        Page<QuotaLedgerEntity> page = new Page<>(1, 20, 4);
        page.setRecords(List.of(
                visible,
                hiddenCompensation,
                hiddenMigration,
                hiddenMigrationRefund));

        when(quotaLedgerMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        when(quotaLedgerAllocationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        QuotaDomainServiceImpl service = service();

        QuotaLedgerPageResult result = service.getLedgerPage("user_1", null, 1, 20);

        assertEquals(1, result.items().size());
        assertEquals("recharge", result.items().get(0).ledgerType());
    }

    @Test
    void getUserQuota_migratesLegacyDetectionBalanceIntoAddonWords() {
        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode("ai_detection");
        featureDef.setFeatureName("AI Detection");
        featureDef.setQuotaUnit("words");
        featureDef.setFreeQuotaPeriod("daily");
        featureDef.setFreeQuotaAmount(1L);
        featureDef.setIsActive(true);

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(12L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("ai_detection");
        quota.setFreeBalance(1L);
        quota.setPlanBalance(30_000L);
        quota.setPaidBalance(210_000L);
        quota.setVersion(0);

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        UserAddonGrantEntity migratedGrant = new UserAddonGrantEntity();
        migratedGrant.setId(301L);
        migratedGrant.setGrantType("legacy_migration");
        migratedGrant.setStatus("active");
        migratedGrant.setInitialAmount(210_000L);
        migratedGrant.setRemainingAmount(210_000L);
        migratedGrant.setExpiresAt(LocalDateTime.now().plusMonths(6));

        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of(migratedGrant));
        when(userAddonGrantMapper.insert(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = new QuotaDomainServiceImpl(
                aiFeatureDefsMapper,
                aiFeaturePackageMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userAddonGrantMapper,
                quotaLedgerAllocationMapper,
                planQuotaService,
                userSubscriptionMapper);

        QuotaBalance balance = service.getUserQuota("user_1", "ai_detection");

        assertEquals(210_000L, balance.addonBalance());
        assertEquals(0L, balance.legacyBalance());
        assertEquals(240_000L, balance.paidBalance());
        assertEquals(240_001L, balance.totalAvailable());
    }

    @Test
    void getUserQuota_usesResolvedSimulationTimeForFreeRefresh() {
        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode("task_create");
        featureDef.setFeatureName("Assignment");
        featureDef.setQuotaUnit("count");
        featureDef.setFreeQuotaPeriod("daily");
        featureDef.setFreeQuotaAmount(3L);
        featureDef.setIsActive(true);

        LocalDateTime fallbackNow = LocalDateTime.parse("2026-06-25T10:00:00");
        LocalDateTime simulatedNow = fallbackNow.plusDays(2);

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(15L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(1L);
        quota.setFreePeriodStart(fallbackNow.minusDays(1));
        quota.setFreePeriodEnd(fallbackNow.plusHours(2));
        quota.setPlanBalance(0L);
        quota.setPaidBalance(0L);
        quota.setVersion(0);

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = serviceWithResolvedNow(simulatedNow);

        QuotaBalance balance = service.getUserQuota("user_1", "task_create");

        assertEquals(3L, balance.freeBalance());
        verify(userAiQuotaMapper).updateById(any(UserAiQuotaEntity.class));
    }

    @Test
    void getUserQuota_usesResolvedSimulationTimeForAddonExpiry() {
        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode("task_create");
        featureDef.setFeatureName("Assignment");
        featureDef.setQuotaUnit("count");
        featureDef.setFreeQuotaPeriod("monthly");
        featureDef.setFreeQuotaAmount(0L);
        featureDef.setIsActive(true);

        LocalDateTime fallbackNow = LocalDateTime.parse("2026-06-25T10:00:00");
        LocalDateTime simulatedNow = fallbackNow.plusDays(5);

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(16L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(0L);
        quota.setPlanBalance(0L);
        quota.setPaidBalance(0L);
        quota.setVersion(0);

        UserAddonGrantEntity addonGrant = new UserAddonGrantEntity();
        addonGrant.setId(401L);
        addonGrant.setGrantType("addon");
        addonGrant.setStatus("active");
        addonGrant.setInitialAmount(3L);
        addonGrant.setRemainingAmount(3L);
        addonGrant.setExpiresAt(fallbackNow.plusDays(1));

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of(addonGrant));

        QuotaDomainServiceImpl service = serviceWithResolvedNow(simulatedNow);

        QuotaBalance balance = service.getUserQuota("user_1", "task_create");

        assertEquals(0L, balance.addonBalance());
        assertEquals(0L, balance.totalAvailable());
    }

    @Test
    void getUserQuota_reanchorsFutureFreeWindowBackToResolvedNow() {
        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode("task_create");
        featureDef.setFeatureName("Assignment");
        featureDef.setQuotaUnit("count");
        featureDef.setFreeQuotaPeriod("daily");
        featureDef.setFreeQuotaAmount(3L);
        featureDef.setIsActive(true);

        LocalDateTime resolvedNow = LocalDateTime.parse("2026-06-25T10:00:00");
        LocalDateTime futureStart = resolvedNow.plusDays(5);

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(17L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(1L);
        quota.setFreePeriodStart(futureStart);
        quota.setFreePeriodEnd(futureStart.plusDays(1));
        quota.setPlanBalance(0L);
        quota.setPaidBalance(0L);
        quota.setVersion(0);

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = serviceWithResolvedNow(resolvedNow);

        QuotaBalance balance = service.getUserQuota("user_1", "task_create");

        assertEquals(3L, balance.freeBalance());
        assertEquals(resolvedNow.plusDays(1), balance.freePeriodEnd());

        ArgumentCaptor<UserAiQuotaEntity> quotaCaptor = ArgumentCaptor.forClass(UserAiQuotaEntity.class);
        verify(userAiQuotaMapper).updateById(quotaCaptor.capture());
        assertEquals(resolvedNow, quotaCaptor.getValue().getFreePeriodStart());
        assertEquals(resolvedNow.plusDays(1), quotaCaptor.getValue().getFreePeriodEnd());
    }

    @Test
    void getUserQuota_reanchorsFutureAddonGrantBackToResolvedNow() {
        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode("task_create");
        featureDef.setFeatureName("Assignment");
        featureDef.setQuotaUnit("count");
        featureDef.setFreeQuotaPeriod("monthly");
        featureDef.setFreeQuotaAmount(0L);
        featureDef.setIsActive(true);

        LocalDateTime resolvedNow = LocalDateTime.parse("2026-06-25T10:00:00");
        LocalDateTime futurePurchaseTime = resolvedNow.plusDays(5);

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(18L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(0L);
        quota.setPlanBalance(0L);
        quota.setPaidBalance(0L);
        quota.setVersion(0);

        UserAddonGrantEntity addonGrant = new UserAddonGrantEntity();
        addonGrant.setId(501L);
        addonGrant.setGrantType("addon");
        addonGrant.setStatus("active");
        addonGrant.setInitialAmount(3L);
        addonGrant.setRemainingAmount(3L);
        addonGrant.setPurchasedAt(futurePurchaseTime);
        addonGrant.setExpiresAt(futurePurchaseTime.plusDays(30));

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of(addonGrant));
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = serviceWithResolvedNow(resolvedNow);

        QuotaBalance balance = service.getUserQuota("user_1", "task_create");

        assertEquals(3L, balance.addonBalance());

        ArgumentCaptor<UserAddonGrantEntity> grantCaptor = ArgumentCaptor.forClass(UserAddonGrantEntity.class);
        verify(userAddonGrantMapper).updateById(grantCaptor.capture());
        assertEquals(resolvedNow, grantCaptor.getValue().getPurchasedAt());
        assertEquals(resolvedNow.plusDays(30), grantCaptor.getValue().getExpiresAt());
        assertEquals("active", grantCaptor.getValue().getStatus());
    }

    @Test
    void consume_debitsDetectionLegacyBalanceByWords() {
        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode("ai_detection");
        featureDef.setFeatureName("AI Detection");
        featureDef.setQuotaUnit("words");
        featureDef.setFreeQuotaPeriod("daily");
        featureDef.setFreeQuotaAmount(0L);
        featureDef.setIsActive(true);

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(12L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("ai_detection");
        quota.setFreeBalance(0L);
        quota.setPlanBalance(0L);
        quota.setPaidBalance(210_000L);
        quota.setVersion(0);

        UserAddonGrantEntity migratedGrant = new UserAddonGrantEntity();
        migratedGrant.setId(302L);
        migratedGrant.setGrantType("legacy_migration");
        migratedGrant.setStatus("active");
        migratedGrant.setInitialAmount(210_000L);
        migratedGrant.setRemainingAmount(210_000L);
        migratedGrant.setExpiresAt(LocalDateTime.now().plusMonths(6));

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of(migratedGrant));
        when(userAddonGrantMapper.insert(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenAnswer(invocation -> {
            QuotaLedgerEntity ledger = invocation.getArgument(0);
            if (ledger.getId() == null) {
                ledger.setId("consume".equals(ledger.getLedgerType()) ? 601L : 600L);
            }
            return 1;
        });
        when(quotaLedgerAllocationMapper.insert(any(QuotaLedgerAllocationEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = new QuotaDomainServiceImpl(
                aiFeatureDefsMapper,
                aiFeaturePackageMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userAddonGrantMapper,
                quotaLedgerAllocationMapper,
                planQuotaService,
                userSubscriptionMapper);

        ConsumeResult result = service.consume(
                "user_1",
                "ai_detection",
                500L,
                "verla_session",
                "session_1",
                Map.of());

        assertEquals(601L, result.ledgerId());

        ArgumentCaptor<UserAiQuotaEntity> quotaCaptor = ArgumentCaptor.forClass(UserAiQuotaEntity.class);
        verify(userAiQuotaMapper, times(2)).updateById(quotaCaptor.capture());
        assertEquals(0L, quotaCaptor.getAllValues().get(0).getPaidBalance());
        assertEquals(0L, quotaCaptor.getAllValues().get(1).getPaidBalance());

        ArgumentCaptor<QuotaLedgerEntity> ledgerCaptor = ArgumentCaptor.forClass(QuotaLedgerEntity.class);
        verify(quotaLedgerMapper, times(2)).insert(ledgerCaptor.capture());
        QuotaLedgerEntity ledger = ledgerCaptor.getAllValues().stream()
                .filter(item -> "consume".equals(item.getLedgerType()))
                .findFirst()
                .orElseThrow();
        assertEquals(-500L, ledger.getAmount());
        assertEquals(0L, ledger.getPaidBalanceAfter());

        ArgumentCaptor<QuotaLedgerAllocationEntity> allocationCaptor =
                ArgumentCaptor.forClass(QuotaLedgerAllocationEntity.class);
        verify(quotaLedgerAllocationMapper).insert(allocationCaptor.capture());
        assertEquals("addon", allocationCaptor.getValue().getPoolType());
        assertEquals(500L, allocationCaptor.getValue().getAmount());
    }

    @Test
    void getAllUserQuotas_refreshesPlanQuotasOnceBeforeBuildingBalances() {
        AiFeatureDefsEntity assignment = new AiFeatureDefsEntity();
        assignment.setFeatureCode("task_create");
        assignment.setFeatureName("Assignment");
        assignment.setQuotaUnit("count");
        assignment.setFreeQuotaPeriod("monthly");
        assignment.setFreeQuotaAmount(1L);
        assignment.setIsActive(true);
        assignment.setDisplayOrder(1);

        AiFeatureDefsEntity detection = new AiFeatureDefsEntity();
        detection.setFeatureCode("ai_detection");
        detection.setFeatureName("AI Detection");
        detection.setQuotaUnit("words");
        detection.setFreeQuotaPeriod("monthly");
        detection.setFreeQuotaAmount(3000L);
        detection.setIsActive(true);
        detection.setDisplayOrder(2);

        UserAiQuotaEntity assignmentQuota = new UserAiQuotaEntity();
        assignmentQuota.setId(11L);
        assignmentQuota.setClerkUserId("user_1");
        assignmentQuota.setFeatureCode("task_create");
        assignmentQuota.setFreeBalance(1L);
        assignmentQuota.setPlanBalance(0L);
        assignmentQuota.setPaidBalance(0L);
        assignmentQuota.setVersion(0);

        UserAiQuotaEntity detectionQuota = new UserAiQuotaEntity();
        detectionQuota.setId(12L);
        detectionQuota.setClerkUserId("user_1");
        detectionQuota.setFeatureCode("ai_detection");
        detectionQuota.setFreeBalance(3000L);
        detectionQuota.setPlanBalance(0L);
        detectionQuota.setPaidBalance(0L);
        detectionQuota.setVersion(0);

        when(aiFeatureDefsMapper.selectList(any(Wrapper.class))).thenReturn(List.of(assignment, detection));
        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(assignment, detection);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(assignmentQuota, detectionQuota);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        QuotaDomainServiceImpl service = new QuotaDomainServiceImpl(
                aiFeatureDefsMapper,
                aiFeaturePackageMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userAddonGrantMapper,
                quotaLedgerAllocationMapper,
                planQuotaService,
                userSubscriptionMapper);

        List<QuotaBalance> balances = service.getAllUserQuotas("user_1");

        assertEquals(2, balances.size());
        verify(planQuotaService, times(1)).refreshAllPlanQuotasIfNeeded("user_1");
        verify(planQuotaService, never()).refreshPlanQuotaIfNeeded(any(), any());
    }

    @Test
    void consume_prefersFree_thenPlan_thenAddon_thenLegacy() {
        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode("task_create");
        featureDef.setFeatureName("Assignment");
        featureDef.setQuotaUnit("count");
        featureDef.setFreeQuotaPeriod("monthly");
        featureDef.setFreeQuotaAmount(1L);
        featureDef.setIsActive(true);

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(11L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(1L);
        quota.setPlanBalance(2L);
        quota.setPlanPeriodEnd(LocalDateTime.parse("2026-07-15T00:00:00"));
        quota.setPaidBalance(0L);
        quota.setVersion(0);

        UserAddonGrantEntity addonGrant = new UserAddonGrantEntity();
        addonGrant.setId(99L);
        addonGrant.setClerkUserId("user_1");
        addonGrant.setFeatureCode("task_create");
        addonGrant.setAddonCode("addon_assignment_3");
        addonGrant.setGrantType("addon");
        addonGrant.setStatus("active");
        addonGrant.setInitialAmount(3L);
        addonGrant.setRemainingAmount(3L);
        addonGrant.setExpiresAt(LocalDateTime.now().plusDays(7));

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of(addonGrant));
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenAnswer(invocation -> {
            QuotaLedgerEntity ledger = invocation.getArgument(0);
            if (ledger.getId() == null) {
                ledger.setId(501L);
            }
            return 1;
        });
        when(quotaLedgerAllocationMapper.insert(any(QuotaLedgerAllocationEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = new QuotaDomainServiceImpl(
                aiFeatureDefsMapper,
                aiFeaturePackageMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userAddonGrantMapper,
                quotaLedgerAllocationMapper,
                planQuotaService,
                userSubscriptionMapper);

        ConsumeResult result = service.consume(
                "user_1",
                "task_create",
                5L,
                "verla_session",
                "session_1",
                Map.of("conversation_id", 123L));

        assertEquals(501L, result.ledgerId());

        ArgumentCaptor<QuotaLedgerEntity> ledgerCaptor = ArgumentCaptor.forClass(QuotaLedgerEntity.class);
        verify(quotaLedgerMapper).insert(ledgerCaptor.capture());
        QuotaLedgerEntity ledger = ledgerCaptor.getValue();
        assertEquals(0L, ledger.getFreeBalanceAfter());
        assertEquals(0L, ledger.getPlanBalanceAfter());
        assertEquals(1L, ledger.getAddonBalanceAfter());
        assertEquals(0L, ledger.getPaidBalanceAfter());

        ArgumentCaptor<QuotaLedgerAllocationEntity> allocationCaptor =
                ArgumentCaptor.forClass(QuotaLedgerAllocationEntity.class);
        verify(quotaLedgerAllocationMapper, times(3)).insert(allocationCaptor.capture());

        List<QuotaLedgerAllocationEntity> allocations = allocationCaptor.getAllValues();
        assertEquals("free", allocations.get(0).getPoolType());
        assertEquals(1L, allocations.get(0).getAmount());
        assertEquals("plan", allocations.get(1).getPoolType());
        assertEquals(2L, allocations.get(1).getAmount());
        assertEquals(quota.getPlanPeriodEnd(), allocations.get(1).getSourcePeriodEnd());
        assertEquals("addon", allocations.get(2).getPoolType());
        assertEquals(99L, allocations.get(2).getGrantId());
        assertEquals(2L, allocations.get(2).getAmount());
        assertEquals(addonGrant.getExpiresAt(), allocations.get(2).getSourcePeriodEnd());
    }

    @Test
    void consume_shouldStampLedgerCreatedAtWithResolvedAppTime() {
        LocalDateTime resolvedNow = LocalDateTime.of(2026, 6, 26, 13, 23, 4);

        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode("humanizer");
        featureDef.setFeatureName("Humanizer");
        featureDef.setQuotaUnit("words");
        featureDef.setFreeQuotaPeriod("monthly");
        featureDef.setFreeQuotaAmount(1000L);
        featureDef.setIsActive(true);

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(21L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("humanizer");
        quota.setFreeBalance(1000L);
        quota.setPlanBalance(10L);
        quota.setPaidBalance(0L);
        quota.setVersion(0);

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenAnswer(invocation -> {
            QuotaLedgerEntity ledger = invocation.getArgument(0);
            ledger.setId(7858L);
            return 1;
        });
        when(quotaLedgerAllocationMapper.insert(any(QuotaLedgerAllocationEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = serviceWithResolvedNow(resolvedNow);
        service.consume("user_1", "humanizer", 223L, "verla_session", "6776", Map.of());

        ArgumentCaptor<QuotaLedgerEntity> ledgerCaptor = ArgumentCaptor.forClass(QuotaLedgerEntity.class);
        verify(quotaLedgerMapper).insert(ledgerCaptor.capture());
        assertEquals(resolvedNow, ledgerCaptor.getValue().getCreatedAt());
        assertEquals("consume", ledgerCaptor.getValue().getLedgerType());
    }

    @Test
    void consume_assignment_debitsFreeBeforePlanAndAddon() {
        UserAiQuotaEntity quota = assignmentQuota(1L, 2L, 0L);
        UserAddonGrantEntity addonGrant = addonGrant(99L, "active", 3L, LocalDateTime.now().plusDays(7));

        stubAssignmentConsume(quota, List.of(addonGrant), 701L);

        QuotaDomainServiceImpl service = service();
        ConsumeResult result = consumeAssignment(service, "session_free");

        assertEquals(701L, result.ledgerId());
        assertEquals(0L, quota.getFreeBalance());
        assertEquals(2L, quota.getPlanBalance());
        assertEquals(3L, addonGrant.getRemainingAmount());
        verify(userAddonGrantMapper, never()).updateById(any(UserAddonGrantEntity.class));

        QuotaLedgerEntity ledger = captureOnlyLedger();
        assertEquals(0L, ledger.getFreeBalanceAfter());
        assertEquals(2L, ledger.getPlanBalanceAfter());
        assertEquals(3L, ledger.getAddonBalanceAfter());

        List<QuotaLedgerAllocationEntity> allocations = captureAllocations(1);
        assertEquals("free", allocations.get(0).getPoolType());
        assertEquals(1L, allocations.get(0).getAmount());
    }

    @Test
    void consume_assignment_debitsPlanBeforeAddonWhenFreeExhausted() {
        UserAiQuotaEntity quota = assignmentQuota(0L, 2L, 0L);
        UserAddonGrantEntity addonGrant = addonGrant(99L, "active", 3L, LocalDateTime.now().plusDays(7));

        stubAssignmentConsume(quota, List.of(addonGrant), 702L);

        QuotaDomainServiceImpl service = service();
        ConsumeResult result = consumeAssignment(service, "session_plan");

        assertEquals(702L, result.ledgerId());
        assertEquals(0L, quota.getFreeBalance());
        assertEquals(1L, quota.getPlanBalance());
        assertEquals(3L, addonGrant.getRemainingAmount());
        verify(userAddonGrantMapper, never()).updateById(any(UserAddonGrantEntity.class));

        QuotaLedgerEntity ledger = captureOnlyLedger();
        assertEquals(0L, ledger.getFreeBalanceAfter());
        assertEquals(1L, ledger.getPlanBalanceAfter());
        assertEquals(3L, ledger.getAddonBalanceAfter());

        List<QuotaLedgerAllocationEntity> allocations = captureAllocations(1);
        assertEquals("plan", allocations.get(0).getPoolType());
        assertEquals(1L, allocations.get(0).getAmount());
    }

    @Test
    void consume_assignment_debitsActiveAddonWhenFreeAndPlanExhausted() {
        UserAiQuotaEntity quota = assignmentQuota(0L, 0L, 0L);
        UserAddonGrantEntity addonGrant = addonGrant(99L, "active", 3L, LocalDateTime.now().plusDays(7));

        stubAssignmentConsume(quota, List.of(addonGrant), 703L);
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = service();
        ConsumeResult result = consumeAssignment(service, "session_addon");

        assertEquals(703L, result.ledgerId());
        assertEquals(2L, addonGrant.getRemainingAmount());
        assertEquals("active", addonGrant.getStatus());
        verify(userAddonGrantMapper).updateById(addonGrant);

        QuotaLedgerEntity ledger = captureOnlyLedger();
        assertEquals(2L, ledger.getAddonBalanceAfter());

        List<QuotaLedgerAllocationEntity> allocations = captureAllocations(1);
        assertEquals("addon", allocations.get(0).getPoolType());
        assertEquals(99L, allocations.get(0).getGrantId());
        assertEquals(1L, allocations.get(0).getAmount());
    }

    @Test
    void consume_assignmentRejectsPausedAddonWhenMemberExpired() {
        UserAiQuotaEntity quota = assignmentQuota(0L, 0L, 0L);

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(assignmentFeature());
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        QuotaDomainServiceImpl service = service();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> consumeAssignment(service, "session_paused_addon"));

        assertTrue(ex.getMessage().contains("Insufficient quota"));
        verify(userAddonGrantMapper, never()).updateById(any(UserAddonGrantEntity.class));
        verify(userAiQuotaMapper, never()).updateById(any(UserAiQuotaEntity.class));
        verify(quotaLedgerMapper, never()).insert(any(QuotaLedgerEntity.class));
        verify(quotaLedgerAllocationMapper, never()).insert(any(QuotaLedgerAllocationEntity.class));
    }

    @Test
    void consume_assignmentRejectsExpiredAddon() {
        UserAiQuotaEntity quota = assignmentQuota(0L, 0L, 0L);

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(assignmentFeature());
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        QuotaDomainServiceImpl service = service();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> consumeAssignment(service, "session_expired_addon"));

        assertTrue(ex.getMessage().contains("Insufficient quota"));
        verify(userAddonGrantMapper, never()).updateById(any(UserAddonGrantEntity.class));
        verify(userAiQuotaMapper, never()).updateById(any(UserAiQuotaEntity.class));
        verify(quotaLedgerMapper, never()).insert(any(QuotaLedgerEntity.class));
        verify(quotaLedgerAllocationMapper, never()).insert(any(QuotaLedgerAllocationEntity.class));
    }

    @Test
    void consume_assignmentUsesEarliestExpiringAddonFirst() {
        UserAiQuotaEntity quota = assignmentQuota(0L, 0L, 0L);
        UserAddonGrantEntity earlierGrant = addonGrant(100L, "active", 3L, LocalDateTime.now().plusDays(1));
        UserAddonGrantEntity laterGrant = addonGrant(101L, "active", 3L, LocalDateTime.now().plusDays(7));

        stubAssignmentConsume(quota, List.of(laterGrant, earlierGrant), 704L);
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = service();
        ConsumeResult result = consumeAssignment(service, "session_earliest_addon");

        assertEquals(704L, result.ledgerId());
        assertEquals(2L, earlierGrant.getRemainingAmount());
        assertEquals(3L, laterGrant.getRemainingAmount());

        ArgumentCaptor<UserAddonGrantEntity> grantCaptor = ArgumentCaptor.forClass(UserAddonGrantEntity.class);
        verify(userAddonGrantMapper).updateById(grantCaptor.capture());
        assertEquals(100L, grantCaptor.getValue().getId());

        List<QuotaLedgerAllocationEntity> allocations = captureAllocations(1);
        assertEquals(100L, allocations.get(0).getGrantId());
    }

    @Test
    void consume_assignmentSpansMultipleAddonsAcrossSequentialCreations() {
        UserAiQuotaEntity quota = assignmentQuota(0L, 0L, 0L);
        UserAddonGrantEntity firstGrant = addonGrant(100L, "active", 1L, LocalDateTime.now().plusDays(1));
        UserAddonGrantEntity secondGrant = addonGrant(101L, "active", 3L, LocalDateTime.now().plusDays(7));

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(assignmentFeature());
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(secondGrant, firstGrant), List.of(secondGrant));
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenAnswer(invocation -> {
            QuotaLedgerEntity ledger = invocation.getArgument(0);
            ledger.setId(705L);
            return 1;
        });
        when(quotaLedgerAllocationMapper.insert(any(QuotaLedgerAllocationEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = service();
        ConsumeResult first = consumeAssignment(service, "session_addon_a");
        ConsumeResult second = consumeAssignment(service, "session_addon_b");

        assertEquals(705L, first.ledgerId());
        assertEquals(705L, second.ledgerId());
        assertEquals(0L, firstGrant.getRemainingAmount());
        assertEquals("depleted", firstGrant.getStatus());
        assertEquals(2L, secondGrant.getRemainingAmount());

        ArgumentCaptor<UserAddonGrantEntity> grantCaptor = ArgumentCaptor.forClass(UserAddonGrantEntity.class);
        verify(userAddonGrantMapper, times(2)).updateById(grantCaptor.capture());
        assertEquals(List.of(100L, 101L), grantCaptor.getAllValues().stream()
                .map(UserAddonGrantEntity::getId)
                .toList());

        List<QuotaLedgerAllocationEntity> allocations = captureAllocations(2);
        assertEquals(100L, allocations.get(0).getGrantId());
        assertEquals(101L, allocations.get(1).getGrantId());
    }

    @Test
    void consume_returnsExistingLedger_whenIdempotencyKeyRepeated() {
        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode("task_create");
        featureDef.setFeatureName("Assignment");
        featureDef.setQuotaUnit("count");
        featureDef.setFreeQuotaPeriod("monthly");
        featureDef.setFreeQuotaAmount(1L);
        featureDef.setIsActive(true);

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(11L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(1L);
        quota.setPlanBalance(0L);
        quota.setPaidBalance(0L);
        quota.setVersion(0);

        QuotaLedgerEntity existingLedger = new QuotaLedgerEntity();
        existingLedger.setId(501L);

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null, existingLedger);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenAnswer(invocation -> {
            QuotaLedgerEntity ledger = invocation.getArgument(0);
            ledger.setId(501L);
            return 1;
        });
        when(quotaLedgerAllocationMapper.insert(any(QuotaLedgerAllocationEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = new QuotaDomainServiceImpl(
                aiFeatureDefsMapper,
                aiFeaturePackageMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userAddonGrantMapper,
                quotaLedgerAllocationMapper,
                planQuotaService,
                userSubscriptionMapper);

        ConsumeResult first = service.consume(
                "user_1",
                "task_create",
                1L,
                "verla_session",
                "session_1",
                Map.of("conversation_id", 123L),
                "assignment:123:generate");
        ConsumeResult second = service.consume(
                "user_1",
                "task_create",
                1L,
                "verla_session",
                "session_1",
                Map.of("conversation_id", 123L),
                "assignment:123:generate");

        assertEquals(501L, first.ledgerId());
        assertEquals(501L, second.ledgerId());
        verify(userAiQuotaMapper, times(1)).updateById(any(UserAiQuotaEntity.class));
        verify(quotaLedgerMapper, times(1)).insert(any(QuotaLedgerEntity.class));
        verify(quotaLedgerAllocationMapper, times(1)).insert(any(QuotaLedgerAllocationEntity.class));
    }

    @Test
    void refund_restoresOriginalAllocationsPrecisely() {
        QuotaLedgerEntity consumeLedger = new QuotaLedgerEntity();
        consumeLedger.setId(501L);
        consumeLedger.setClerkUserId("user_1");
        consumeLedger.setFeatureCode("task_create");
        consumeLedger.setLedgerType("consume");
        consumeLedger.setAmount(-5L);
        consumeLedger.setSourceType("verla_session");
        consumeLedger.setSourceId("session_1");

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(11L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(0L);
        quota.setPlanBalance(0L);
        quota.setPaidBalance(4L);
        quota.setVersion(1);

        UserAddonGrantEntity addonGrant = new UserAddonGrantEntity();
        addonGrant.setId(99L);
        addonGrant.setGrantType("addon");
        addonGrant.setStatus("active");
        addonGrant.setRemainingAmount(1L);
        addonGrant.setExpiresAt(LocalDateTime.now().plusDays(7));

        QuotaLedgerAllocationEntity freeAllocation = new QuotaLedgerAllocationEntity();
        freeAllocation.setPoolType("free");
        freeAllocation.setAmount(1L);
        QuotaLedgerAllocationEntity planAllocation = new QuotaLedgerAllocationEntity();
        planAllocation.setPoolType("plan");
        planAllocation.setAmount(2L);
        QuotaLedgerAllocationEntity addonAllocation = new QuotaLedgerAllocationEntity();
        addonAllocation.setPoolType("addon");
        addonAllocation.setGrantId(99L);
        addonAllocation.setAmount(2L);
        addonAllocation.setSourcePeriodEnd(addonGrant.getExpiresAt());

        when(quotaLedgerMapper.selectById(501L)).thenReturn(consumeLedger);
        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(quotaLedgerAllocationMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(freeAllocation, planAllocation, addonAllocation));
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.selectById(99L)).thenReturn(addonGrant);
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = new QuotaDomainServiceImpl(
                aiFeatureDefsMapper,
                aiFeaturePackageMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userAddonGrantMapper,
                quotaLedgerAllocationMapper,
                planQuotaService,
                userSubscriptionMapper);

        service.refund(501L, "task_failed");

        ArgumentCaptor<UserAiQuotaEntity> quotaCaptor = ArgumentCaptor.forClass(UserAiQuotaEntity.class);
        verify(userAiQuotaMapper).updateById(quotaCaptor.capture());
        UserAiQuotaEntity updatedQuota = quotaCaptor.getValue();
        assertEquals(1L, updatedQuota.getFreeBalance());
        assertEquals(2L, updatedQuota.getPlanBalance());
        assertEquals(4L, updatedQuota.getPaidBalance());

        ArgumentCaptor<UserAddonGrantEntity> grantCaptor = ArgumentCaptor.forClass(UserAddonGrantEntity.class);
        verify(userAddonGrantMapper).updateById(grantCaptor.capture());
        assertEquals(3L, grantCaptor.getValue().getRemainingAmount());
        assertEquals("active", grantCaptor.getValue().getStatus());
    }

    @Test
    void refund_createsCompensationGrant_whenOriginalAddonExpired() {
        QuotaLedgerEntity consumeLedger = new QuotaLedgerEntity();
        consumeLedger.setId(502L);
        consumeLedger.setClerkUserId("user_1");
        consumeLedger.setFeatureCode("task_create");
        consumeLedger.setLedgerType("consume");
        consumeLedger.setAmount(-2L);
        consumeLedger.setSourceType("verla_session");
        consumeLedger.setSourceId("session_2");

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(11L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(0L);
        quota.setPlanBalance(0L);
        quota.setPaidBalance(0L);
        quota.setVersion(1);

        UserAddonGrantEntity expiredGrant = new UserAddonGrantEntity();
        expiredGrant.setId(199L);
        expiredGrant.setGrantType("addon");
        expiredGrant.setStatus("depleted");
        expiredGrant.setInitialAmount(3L);
        expiredGrant.setRemainingAmount(0L);
        expiredGrant.setExpiresAt(LocalDateTime.now().minusDays(2));

        UserAddonGrantEntity compensationBalanceGrant = new UserAddonGrantEntity();
        compensationBalanceGrant.setId(299L);
        compensationBalanceGrant.setGrantType("compensation");
        compensationBalanceGrant.setStatus("active");
        compensationBalanceGrant.setInitialAmount(2L);
        compensationBalanceGrant.setRemainingAmount(2L);
        compensationBalanceGrant.setExpiresAt(LocalDateTime.now().plusDays(30));

        QuotaLedgerAllocationEntity addonAllocation = new QuotaLedgerAllocationEntity();
        addonAllocation.setPoolType("addon");
        addonAllocation.setGrantId(199L);
        addonAllocation.setAmount(2L);
        addonAllocation.setSourcePeriodEnd(LocalDateTime.now().minusDays(2));

        when(quotaLedgerMapper.selectById(502L)).thenReturn(consumeLedger);
        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(quotaLedgerAllocationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(addonAllocation));
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.selectById(199L)).thenReturn(expiredGrant);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of(compensationBalanceGrant));
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.insert(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenAnswer(invocation -> {
            QuotaLedgerEntity ledger = invocation.getArgument(0);
            if (ledger.getId() == null) {
                ledger.setId(801L);
            }
            return 1;
        });

        QuotaDomainServiceImpl service = new QuotaDomainServiceImpl(
                aiFeatureDefsMapper,
                aiFeaturePackageMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userAddonGrantMapper,
                quotaLedgerAllocationMapper,
                planQuotaService,
                userSubscriptionMapper);

        service.refund(502L, "task_failed");

        ArgumentCaptor<UserAddonGrantEntity> grantCaptor = ArgumentCaptor.forClass(UserAddonGrantEntity.class);
        verify(userAddonGrantMapper).insert(grantCaptor.capture());
        UserAddonGrantEntity compensationGrant = grantCaptor.getValue();
        assertEquals("compensation", compensationGrant.getGrantType());
        assertEquals("active", compensationGrant.getStatus());
        assertEquals(2L, compensationGrant.getInitialAmount());
        assertEquals(2L, compensationGrant.getRemainingAmount());
        assertNotNull(compensationGrant.getExpiresAt());

        ArgumentCaptor<QuotaLedgerEntity> ledgerCaptor = ArgumentCaptor.forClass(QuotaLedgerEntity.class);
        verify(quotaLedgerMapper, times(2)).insert(ledgerCaptor.capture());
        QuotaLedgerEntity compensationLedger = ledgerCaptor.getAllValues().stream()
                .filter(ledger -> "compensation_grant".equals(ledger.getLedgerType()))
                .findFirst()
                .orElseThrow();
        assertEquals(2L, compensationLedger.getAddonBalanceAfter());
    }

    @Test
    void refund_setsExactIdempotencyKey_onRefundLedger() {
        QuotaLedgerEntity consumeLedger = new QuotaLedgerEntity();
        consumeLedger.setId(5L);
        consumeLedger.setClerkUserId("user_1");
        consumeLedger.setFeatureCode("task_create");
        consumeLedger.setLedgerType("consume");
        consumeLedger.setAmount(-1L);
        consumeLedger.setSourceType("verla_session");
        consumeLedger.setSourceId("session_5");
        consumeLedger.setBizContext("{\"from_free_amount\":1,\"from_plan_amount\":0,\"from_paid_amount\":0}");

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(11L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(0L);
        quota.setPlanBalance(0L);
        quota.setPaidBalance(0L);
        quota.setVersion(1);

        when(quotaLedgerMapper.selectById(5L)).thenReturn(consumeLedger);
        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(quotaLedgerAllocationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = new QuotaDomainServiceImpl(
                aiFeatureDefsMapper,
                aiFeaturePackageMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userAddonGrantMapper,
                quotaLedgerAllocationMapper,
                planQuotaService,
                userSubscriptionMapper);

        service.refund(5L, "task_failed");

        ArgumentCaptor<QuotaLedgerEntity> ledgerCaptor = ArgumentCaptor.forClass(QuotaLedgerEntity.class);
        verify(quotaLedgerMapper).insert(ledgerCaptor.capture());
        assertEquals("refund:5", ledgerCaptor.getValue().getIdempotencyKey());
    }

    @Test
    void consume_handlesActiveGrantWithoutExpiry() {
        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode("task_create");
        featureDef.setFeatureName("Assignment");
        featureDef.setQuotaUnit("count");
        featureDef.setFreeQuotaPeriod("monthly");
        featureDef.setFreeQuotaAmount(0L);
        featureDef.setIsActive(true);

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(11L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(0L);
        quota.setPlanBalance(0L);
        quota.setPaidBalance(0L);
        quota.setVersion(0);

        UserAddonGrantEntity expiringGrant = new UserAddonGrantEntity();
        expiringGrant.setId(100L);
        expiringGrant.setGrantType("addon");
        expiringGrant.setStatus("active");
        expiringGrant.setInitialAmount(1L);
        expiringGrant.setRemainingAmount(1L);
        expiringGrant.setExpiresAt(LocalDateTime.now().plusDays(1));

        UserAddonGrantEntity legacyGrant = new UserAddonGrantEntity();
        legacyGrant.setId(101L);
        legacyGrant.setGrantType("legacy_migration");
        legacyGrant.setStatus("active");
        legacyGrant.setInitialAmount(1L);
        legacyGrant.setRemainingAmount(1L);
        legacyGrant.setExpiresAt(null);

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of(legacyGrant, expiringGrant));
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenAnswer(invocation -> {
            QuotaLedgerEntity ledger = invocation.getArgument(0);
            ledger.setId(611L);
            return 1;
        });
        when(quotaLedgerAllocationMapper.insert(any(QuotaLedgerAllocationEntity.class))).thenReturn(1);

        QuotaDomainServiceImpl service = new QuotaDomainServiceImpl(
                aiFeatureDefsMapper,
                aiFeaturePackageMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userAddonGrantMapper,
                quotaLedgerAllocationMapper,
                planQuotaService,
                userSubscriptionMapper);

        ConsumeResult result = service.consume(
                "user_1",
                "task_create",
                1L,
                "verla_session",
                "session_legacy",
                Map.of());

        assertEquals(611L, result.ledgerId());
    }

    @Test
    void consume_throwsAndSkipsLedger_whenOptimisticUpdateLosesRace() throws Exception {
        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode("task_create");
        featureDef.setFeatureName("Assignment");
        featureDef.setQuotaUnit("count");
        featureDef.setFreeQuotaPeriod("monthly");
        featureDef.setFreeQuotaAmount(0L);
        featureDef.setIsActive(true);

        CountDownLatch bothSelected = new CountDownLatch(2);
        AtomicInteger selectCalls = new AtomicInteger();
        AtomicInteger updateCalls = new AtomicInteger();
        AtomicInteger ledgerInsertCalls = new AtomicInteger();

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            if (selectCalls.incrementAndGet() <= 2) {
                bothSelected.countDown();
                bothSelected.await(2, TimeUnit.SECONDS);
            }
            UserAiQuotaEntity quota = new UserAiQuotaEntity();
            quota.setId(11L);
            quota.setClerkUserId("user_1");
            quota.setFeatureCode("task_create");
            quota.setFreeBalance(0L);
            quota.setPlanBalance(1L);
            quota.setPaidBalance(0L);
            quota.setVersion(0);
            return quota;
        });
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class)))
                .thenAnswer(invocation -> updateCalls.incrementAndGet() == 1 ? 1 : 0);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenAnswer(invocation -> {
            ledgerInsertCalls.incrementAndGet();
            QuotaLedgerEntity ledger = invocation.getArgument(0);
            ledger.setId(700L + ledgerInsertCalls.get());
            return 1;
        });

        QuotaDomainServiceImpl service = new QuotaDomainServiceImpl(
                aiFeatureDefsMapper,
                aiFeaturePackageMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userAddonGrantMapper,
                quotaLedgerAllocationMapper,
                planQuotaService,
                userSubscriptionMapper);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Long> task = () -> service.consume(
                    "user_1",
                    "task_create",
                    1L,
                    "verla_session",
                    "session_1",
                    Map.of()).ledgerId();

            Future<Long> first = executor.submit(task);
            Future<Long> second = executor.submit(task);

            List<Long> successLedgerIds = new ArrayList<>();
            int failures = 0;
            for (Future<Long> future : List.of(first, second)) {
                try {
                    successLedgerIds.add(future.get(3, TimeUnit.SECONDS));
                } catch (ExecutionException ex) {
                    failures++;
                    assertEquals(IllegalStateException.class, ex.getCause().getClass());
                }
            }

            assertEquals(1, successLedgerIds.size());
            assertEquals(1, failures);
            assertEquals(1, ledgerInsertCalls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void consume_throwsAndSkipsLedger_whenAddonGrantUpdateLosesRace() {
        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode("task_create");
        featureDef.setFeatureName("Assignment");
        featureDef.setQuotaUnit("count");
        featureDef.setFreeQuotaPeriod("monthly");
        featureDef.setFreeQuotaAmount(0L);
        featureDef.setIsActive(true);

        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(11L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(0L);
        quota.setPlanBalance(0L);
        quota.setPaidBalance(0L);
        quota.setVersion(0);

        UserAddonGrantEntity addonGrant = new UserAddonGrantEntity();
        addonGrant.setId(99L);
        addonGrant.setGrantType("addon");
        addonGrant.setStatus("active");
        addonGrant.setInitialAmount(1L);
        addonGrant.setRemainingAmount(1L);
        addonGrant.setExpiresAt(LocalDateTime.now().plusDays(7));

        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(featureDef);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of(addonGrant));
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(0);

        QuotaDomainServiceImpl service = new QuotaDomainServiceImpl(
                aiFeatureDefsMapper,
                aiFeaturePackageMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userAddonGrantMapper,
                quotaLedgerAllocationMapper,
                planQuotaService,
                userSubscriptionMapper);

        assertThrows(IllegalStateException.class, () -> service.consume(
                "user_1",
                "task_create",
                1L,
                "verla_session",
                "session_1",
                Map.of()));

        verify(quotaLedgerMapper, never()).insert(any(QuotaLedgerEntity.class));
    }

    private QuotaDomainServiceImpl service() {
        return new QuotaDomainServiceImpl(
                aiFeatureDefsMapper,
                aiFeaturePackageMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userAddonGrantMapper,
                quotaLedgerAllocationMapper,
                planQuotaService,
                userSubscriptionMapper);
    }

    private QuotaDomainServiceImpl serviceWithResolvedNow(LocalDateTime resolvedNow) {
        return new QuotaDomainServiceImpl(
                aiFeatureDefsMapper,
                aiFeaturePackageMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userAddonGrantMapper,
                quotaLedgerAllocationMapper,
                planQuotaService,
                userSubscriptionMapper) {
            @Override
            LocalDateTime resolveQuotaNow(String clerkUserId, LocalDateTime fallbackNow) {
                return resolvedNow;
            }
        };
    }

    private AiFeatureDefsEntity assignmentFeature() {
        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode("task_create");
        featureDef.setFeatureName("Assignment");
        featureDef.setQuotaUnit("count");
        featureDef.setFreeQuotaPeriod("monthly");
        featureDef.setFreeQuotaAmount(0L);
        featureDef.setIsActive(true);
        return featureDef;
    }

    private UserAiQuotaEntity assignmentQuota(long freeBalance, long planBalance, long paidBalance) {
        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(11L);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode("task_create");
        quota.setFreeBalance(freeBalance);
        quota.setPlanBalance(planBalance);
        quota.setPaidBalance(paidBalance);
        quota.setVersion(0);
        return quota;
    }

    private UserAddonGrantEntity addonGrant(Long id, String status, long remainingAmount, LocalDateTime expiresAt) {
        UserAddonGrantEntity grant = new UserAddonGrantEntity();
        grant.setId(id);
        grant.setClerkUserId("user_1");
        grant.setFeatureCode("task_create");
        grant.setAddonCode("addon_assignment_3");
        grant.setGrantType("addon");
        grant.setStatus(status);
        grant.setInitialAmount(remainingAmount);
        grant.setRemainingAmount(remainingAmount);
        grant.setExpiresAt(expiresAt);
        return grant;
    }

    private void stubAssignmentConsume(
            UserAiQuotaEntity quota,
            List<UserAddonGrantEntity> addonGrants,
            long ledgerId) {
        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(assignmentFeature());
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(addonGrants);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenAnswer(invocation -> {
            QuotaLedgerEntity ledger = invocation.getArgument(0);
            ledger.setId(ledgerId);
            return 1;
        });
        when(quotaLedgerAllocationMapper.insert(any(QuotaLedgerAllocationEntity.class))).thenReturn(1);
    }

    private ConsumeResult consumeAssignment(QuotaDomainServiceImpl service, String sessionId) {
        return service.consume(
                "user_1",
                "task_create",
                1L,
                "verla_session",
                sessionId,
                Map.of());
    }

    private QuotaLedgerEntity captureOnlyLedger() {
        ArgumentCaptor<QuotaLedgerEntity> ledgerCaptor = ArgumentCaptor.forClass(QuotaLedgerEntity.class);
        verify(quotaLedgerMapper).insert(ledgerCaptor.capture());
        return ledgerCaptor.getValue();
    }

    private List<QuotaLedgerAllocationEntity> captureAllocations(int count) {
        ArgumentCaptor<QuotaLedgerAllocationEntity> allocationCaptor =
                ArgumentCaptor.forClass(QuotaLedgerAllocationEntity.class);
        verify(quotaLedgerAllocationMapper, times(count)).insert(allocationCaptor.capture());
        return allocationCaptor.getAllValues();
    }
}
