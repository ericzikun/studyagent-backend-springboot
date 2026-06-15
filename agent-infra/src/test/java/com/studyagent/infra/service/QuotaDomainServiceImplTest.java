package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import com.studyagent.service.domain.quota.ConsumeResult;
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
        quota.setPaidBalance(4L);
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
                quotaLedgerAllocationMapper);

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
        assertEquals(4L, ledger.getPaidBalanceAfter());

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
                quotaLedgerAllocationMapper);

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
                quotaLedgerAllocationMapper);

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
                quotaLedgerAllocationMapper);

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
        legacyGrant.setGrantType("legacy");
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
                quotaLedgerAllocationMapper);

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
                quotaLedgerAllocationMapper);

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
                quotaLedgerAllocationMapper);

        assertThrows(IllegalStateException.class, () -> service.consume(
                "user_1",
                "task_create",
                1L,
                "verla_session",
                "session_1",
                Map.of()));

        verify(quotaLedgerMapper, never()).insert(any(QuotaLedgerEntity.class));
    }
}
