package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanQuotaServiceImplTest {
    @Mock
    private SubscriptionPlanMapper subscriptionPlanMapper;
    @Mock
    private AiFeatureDefsMapper aiFeatureDefsMapper;
    @Mock
    private UserAiQuotaMapper userAiQuotaMapper;
    @Mock
    private QuotaLedgerMapper quotaLedgerMapper;
    @Mock
    private UserSubscriptionMapper userSubscriptionMapper;

    @Test
    void resetFromPaidInvoice_isIdempotent_perInvoiceAndFeature() {
        SubscriptionPlanEntity plan = plan("basic_monthly", 3L, 3L, 2L);
        UserAiQuotaEntity assignment = quota(11L, "task_create", 1L, 0L, 0L);
        UserAiQuotaEntity detection = quota(12L, "ai_detection", 1L, 1L, 0L);
        UserAiQuotaEntity humanizer = quota(13L, "humanizer", 1L, 5L, 0L);

        AtomicInteger ledgerChecks = new AtomicInteger();
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(quotaLedgerMapper.selectOne(any(Wrapper.class)))
                .thenAnswer(invocation -> ledgerChecks.incrementAndGet() <= 3 ? null : new QuotaLedgerEntity());
        when(userAiQuotaMapper.selectOne(any(Wrapper.class)))
                .thenReturn(assignment, detection, humanizer);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        PlanQuotaServiceImpl service = new PlanQuotaServiceImpl(
                subscriptionPlanMapper,
                aiFeatureDefsMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        Instant start = Instant.parse("2026-06-15T00:00:00Z");
        Instant end = Instant.parse("2026-07-15T00:00:00Z");
        service.resetFromPaidInvoice("user_1", "sub_1", "basic_monthly", start, end, "in_1");
        service.resetFromPaidInvoice("user_1", "sub_1", "basic_monthly", start, end, "in_1");

        ArgumentCaptor<UserAiQuotaEntity> quotaCaptor = ArgumentCaptor.forClass(UserAiQuotaEntity.class);
        verify(userAiQuotaMapper, times(3)).updateById(quotaCaptor.capture());
        List<UserAiQuotaEntity> updated = quotaCaptor.getAllValues();
        assertEquals(3L, updated.get(0).getPlanBalance());
        assertEquals(3L, updated.get(1).getPlanBalance());
        assertEquals(2L, updated.get(2).getPlanBalance());
        assertEquals(LocalDateTime.ofInstant(end, ZoneOffset.UTC), updated.get(0).getPlanPeriodEnd());
    }

    @Test
    void addFullPlanForUpgrade_addsRemainingBalancePlusNewFullPlan() {
        SubscriptionPlanEntity plan = plan("basic_monthly", 3L, 3L, 2L);
        UserAiQuotaEntity assignment = quota(11L, "task_create", 1L, 2L, 0L);
        UserAiQuotaEntity detection = quota(12L, "ai_detection", 1L, 1L, 0L);
        UserAiQuotaEntity humanizer = quota(13L, "humanizer", 1L, 0L, 0L);

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class)))
                .thenReturn(assignment, detection, humanizer);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        PlanQuotaServiceImpl service = new PlanQuotaServiceImpl(
                subscriptionPlanMapper,
                aiFeatureDefsMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        service.addFullPlanForUpgrade(
                "user_1",
                "sub_1",
                "basic_monthly",
                Instant.parse("2026-06-15T00:00:00Z"),
                Instant.parse("2026-07-15T00:00:00Z"),
                "in_upgrade_1");

        ArgumentCaptor<UserAiQuotaEntity> quotaCaptor = ArgumentCaptor.forClass(UserAiQuotaEntity.class);
        verify(userAiQuotaMapper, times(3)).updateById(quotaCaptor.capture());
        List<UserAiQuotaEntity> updated = quotaCaptor.getAllValues();
        assertEquals(5L, updated.get(0).getPlanBalance());
        assertEquals(4L, updated.get(1).getPlanBalance());
        assertEquals(2L, updated.get(2).getPlanBalance());
    }

    @Test
    void grantUpgradeFromCheckout_addsRemainingBalancePlusNewFullPlan() {
        SubscriptionPlanEntity plan = plan("basic_monthly", 3L, 3L, 2L);
        UserAiQuotaEntity assignment = quota(11L, "task_create", 1L, 2L, 0L);
        UserAiQuotaEntity detection = quota(12L, "ai_detection", 1L, 1L, 0L);
        UserAiQuotaEntity humanizer = quota(13L, "humanizer", 1L, 0L, 0L);

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class)))
                .thenReturn(assignment, detection, humanizer);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        PlanQuotaServiceImpl service = new PlanQuotaServiceImpl(
                subscriptionPlanMapper,
                aiFeatureDefsMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        service.grantUpgradeFromCheckout(
                "user_1",
                "sub_1",
                "basic_monthly",
                Instant.parse("2026-06-15T00:00:00Z"),
                Instant.parse("2026-07-15T00:00:00Z"),
                "upgrade_ord_1");

        ArgumentCaptor<UserAiQuotaEntity> quotaCaptor = ArgumentCaptor.forClass(UserAiQuotaEntity.class);
        verify(userAiQuotaMapper, times(3)).updateById(quotaCaptor.capture());
        List<UserAiQuotaEntity> updated = quotaCaptor.getAllValues();
        assertEquals(5L, updated.get(0).getPlanBalance());
        assertEquals(4L, updated.get(1).getPlanBalance());
        assertEquals(2L, updated.get(2).getPlanBalance());
    }

    @Test
    void clearPlanQuota_isIdempotent_andClearsPlanWindow() {
        UserAiQuotaEntity assignment = quota(11L, "task_create", 1L, 2L, 0L);
        assignment.setPlanPeriodStart(LocalDateTime.parse("2026-06-15T00:00:00"));
        assignment.setPlanPeriodEnd(LocalDateTime.parse("2026-07-15T00:00:00"));
        UserAiQuotaEntity detection = quota(12L, "ai_detection", 1L, 1L, 0L);
        detection.setPlanPeriodStart(LocalDateTime.parse("2026-06-15T00:00:00"));
        detection.setPlanPeriodEnd(LocalDateTime.parse("2026-07-15T00:00:00"));

        AtomicInteger ledgerChecks = new AtomicInteger();
        when(quotaLedgerMapper.selectOne(any(Wrapper.class)))
                .thenAnswer(invocation -> ledgerChecks.incrementAndGet() <= 2 ? null : new QuotaLedgerEntity());
        when(userAiQuotaMapper.selectList(any(Wrapper.class))).thenReturn(List.of(assignment, detection));
        when(userAiQuotaMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        PlanQuotaServiceImpl service = new PlanQuotaServiceImpl(
                subscriptionPlanMapper,
                aiFeatureDefsMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        service.clearPlanQuota("user_1", "sub_1", "subscription:sub_1:deleted");
        service.clearPlanQuota("user_1", "sub_1", "subscription:sub_1:deleted");

        verify(userAiQuotaMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(userAiQuotaMapper, never()).updateById(any(UserAiQuotaEntity.class));
        assertEquals(0L, assignment.getPlanBalance());
        assertNull(assignment.getPlanPeriodStart());
        assertNull(assignment.getPlanPeriodEnd());
        assertEquals(0L, detection.getPlanBalance());
    }

    @Test
    void refreshPlanQuotaIfNeeded_rollsAnnualPlanWindowForwardFromAnchor() {
        SubscriptionPlanEntity plan = plan("pro_yearly", 3L, 3L, 2L);
        plan.setBillingInterval("year");

        UserSubscriptionEntity subscription = subscription(
                "user_1",
                "pro_yearly",
                "active",
                LocalDateTime.parse("2026-01-15T00:00:00"),
                LocalDateTime.parse("2026-12-15T00:00:00"));

        UserAiQuotaEntity quota = quota(11L, "task_create", 1L, 0L, 0L);
        quota.setPlanPeriodStart(LocalDateTime.parse("2026-01-15T00:00:00"));
        quota.setPlanPeriodEnd(LocalDateTime.parse("2026-02-15T00:00:00"));

        when(userSubscriptionMapper.selectByUser("user_1")).thenReturn(subscription);
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(subscription);
        when(userSubscriptionMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota, quota);
        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        PlanQuotaServiceImpl service = new PlanQuotaServiceImpl(
                subscriptionPlanMapper,
                aiFeatureDefsMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        service.refreshPlanQuotaIfNeeded("user_1", "task_create", LocalDateTime.parse("2026-03-20T12:00:00"));

        ArgumentCaptor<UserAiQuotaEntity> quotaCaptor = ArgumentCaptor.forClass(UserAiQuotaEntity.class);
        verify(userAiQuotaMapper).updateById(quotaCaptor.capture());
        UserAiQuotaEntity updated = quotaCaptor.getValue();
        assertEquals(3L, updated.getPlanBalance());
        assertEquals(LocalDateTime.parse("2026-03-15T00:00:00"), updated.getPlanPeriodStart());
        assertEquals(LocalDateTime.parse("2026-04-15T00:00:00"), updated.getPlanPeriodEnd());
    }

    @Test
    void refreshPlanQuotaIfNeeded_resetsTrialingYearlyUpgradeCarryoverAfterFirstMonth() {
        SubscriptionPlanEntity plan = plan("pro_yearly", 16L, 100_000L, 60_000L);
        plan.setBillingInterval("year");

        UserSubscriptionEntity subscription = subscription(
                "user_1",
                "pro_yearly",
                "trialing",
                LocalDateTime.parse("2026-06-24T14:29:25"),
                LocalDateTime.parse("2027-06-24T14:29:25"));

        UserAiQuotaEntity quota = quota(11L, "task_create", 0L, 19L, 0L);
        quota.setPlanPeriodStart(LocalDateTime.parse("2026-06-24T14:29:25"));
        quota.setPlanPeriodEnd(LocalDateTime.parse("2026-07-24T14:29:25"));

        when(userSubscriptionMapper.selectByUser("user_1")).thenReturn(subscription);
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(subscription);
        when(userSubscriptionMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota, quota);
        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        PlanQuotaServiceImpl service = new PlanQuotaServiceImpl(
                subscriptionPlanMapper,
                aiFeatureDefsMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        service.refreshPlanQuotaIfNeeded("user_1", "task_create", LocalDateTime.parse("2026-07-25T12:00:00"));

        ArgumentCaptor<UserAiQuotaEntity> quotaCaptor = ArgumentCaptor.forClass(UserAiQuotaEntity.class);
        verify(userAiQuotaMapper).updateById(quotaCaptor.capture());
        UserAiQuotaEntity updated = quotaCaptor.getValue();
        assertEquals(16L, updated.getPlanBalance());
        assertEquals(LocalDateTime.parse("2026-07-24T14:29:25"), updated.getPlanPeriodStart());
        assertEquals(LocalDateTime.parse("2026-08-24T14:29:25"), updated.getPlanPeriodEnd());
    }

    @Test
    void refreshPlanQuotaIfNeeded_advancesAnnualSubscriptionQuotaWindowForScheduler() {
        SubscriptionPlanEntity plan = plan("pro_yearly", 16L, 100_000L, 60_000L);
        plan.setBillingInterval("year");

        UserSubscriptionEntity subscription = subscription(
                "user_1",
                "pro_yearly",
                "trialing",
                LocalDateTime.parse("2026-06-24T14:29:25"),
                LocalDateTime.parse("2027-06-24T14:29:25"));

        UserAiQuotaEntity quota = quota(11L, "task_create", 0L, 19L, 0L);
        quota.setPlanPeriodStart(LocalDateTime.parse("2026-06-24T14:29:25"));
        quota.setPlanPeriodEnd(LocalDateTime.parse("2026-07-24T14:29:25"));

        when(userSubscriptionMapper.selectByUser("user_1")).thenReturn(subscription);
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(subscription);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota, quota);
        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(userSubscriptionMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        PlanQuotaServiceImpl service = new PlanQuotaServiceImpl(
                subscriptionPlanMapper,
                aiFeatureDefsMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        service.refreshPlanQuotaIfNeeded("user_1", "task_create", LocalDateTime.parse("2026-07-25T12:00:00"));

        verify(userSubscriptionMapper).update(any(), any(Wrapper.class));
        assertEquals(LocalDateTime.parse("2026-07-24T14:29:25"), subscription.getQuotaPeriodStart());
        assertEquals(LocalDateTime.parse("2026-08-24T14:29:25"), subscription.getQuotaPeriodEnd());
    }

    @Test
    void refreshPlanQuotaIfNeeded_skipsLockWhenPlanWindowStillValid() {
        UserSubscriptionEntity subscription = subscription(
                "user_1",
                "basic_monthly",
                "active",
                LocalDateTime.parse("2026-06-15T00:00:00"),
                LocalDateTime.parse("2026-07-15T00:00:00"));

        UserAiQuotaEntity quota = quota(11L, "task_create", 1L, 2L, 0L);
        quota.setPlanPeriodStart(LocalDateTime.parse("2026-06-15T00:00:00"));
        quota.setPlanPeriodEnd(LocalDateTime.parse("2026-07-15T00:00:00"));

        when(userSubscriptionMapper.selectByUser("user_1")).thenReturn(subscription);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota);

        PlanQuotaServiceImpl service = new PlanQuotaServiceImpl(
                subscriptionPlanMapper,
                aiFeatureDefsMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        service.refreshPlanQuotaIfNeeded("user_1", "task_create", LocalDateTime.parse("2026-07-01T12:00:00"));

        verify(userSubscriptionMapper).selectByUser("user_1");
        verify(userSubscriptionMapper, never()).selectByUserForUpdate("user_1");
        verify(subscriptionPlanMapper, never()).selectOne(any(Wrapper.class));
        verify(userAiQuotaMapper, times(1)).selectOne(any(Wrapper.class));
    }

    @Test
    void refreshPlanQuotaIfNeeded_expiresMonthlyPlanWithoutRefill() {
        SubscriptionPlanEntity plan = plan("basic_monthly", 3L, 3L, 2L);
        plan.setBillingInterval("month");

        UserSubscriptionEntity subscription = subscription(
                "user_1",
                "basic_monthly",
                "active",
                LocalDateTime.parse("2026-06-15T00:00:00"),
                LocalDateTime.parse("2026-07-15T00:00:00"));

        UserAiQuotaEntity quota = quota(11L, "task_create", 1L, 2L, 0L);
        quota.setPlanPeriodStart(LocalDateTime.parse("2026-06-15T00:00:00"));
        quota.setPlanPeriodEnd(LocalDateTime.parse("2026-07-15T00:00:00"));

        when(userSubscriptionMapper.selectByUser("user_1")).thenReturn(subscription);
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(subscription);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(quota, quota);
        when(userAiQuotaMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        PlanQuotaServiceImpl service = new PlanQuotaServiceImpl(
                subscriptionPlanMapper,
                aiFeatureDefsMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        service.refreshPlanQuotaIfNeeded("user_1", "task_create", LocalDateTime.parse("2026-07-20T12:00:00"));

        verify(userAiQuotaMapper).update(isNull(), any(Wrapper.class));
        verify(userAiQuotaMapper, never()).updateById(any(UserAiQuotaEntity.class));
        ArgumentCaptor<QuotaLedgerEntity> ledgerCaptor = ArgumentCaptor.forClass(QuotaLedgerEntity.class);
        verify(quotaLedgerMapper).insert(ledgerCaptor.capture());
        QuotaLedgerEntity ledger = ledgerCaptor.getValue();
        assertEquals("plan_expired", ledger.getLedgerType());
        assertEquals(-2L, ledger.getAmount());
    }

    @Test
    void refreshAllPlanQuotasIfNeeded_reusesSingleSubscriptionAndPlanLookupForAllFeatures() {
        SubscriptionPlanEntity plan = plan("pro_yearly", 3L, 4L, 2L);
        plan.setBillingInterval("year");

        UserSubscriptionEntity subscription = subscription(
                "user_1",
                "pro_yearly",
                "active",
                LocalDateTime.parse("2026-01-15T00:00:00"),
                LocalDateTime.parse("2026-12-15T00:00:00"));

        UserAiQuotaEntity taskQuota = quota(11L, "task_create", 1L, 0L, 0L);
        taskQuota.setPlanPeriodStart(LocalDateTime.parse("2026-01-15T00:00:00"));
        taskQuota.setPlanPeriodEnd(LocalDateTime.parse("2026-02-15T00:00:00"));

        UserAiQuotaEntity detectionQuota = quota(12L, "ai_detection", 1L, 0L, 0L);
        detectionQuota.setPlanPeriodStart(LocalDateTime.parse("2026-01-15T00:00:00"));
        detectionQuota.setPlanPeriodEnd(LocalDateTime.parse("2026-02-15T00:00:00"));

        UserAiQuotaEntity humanizerQuota = quota(13L, "humanizer", 1L, 0L, 0L);
        humanizerQuota.setPlanPeriodStart(LocalDateTime.parse("2026-01-15T00:00:00"));
        humanizerQuota.setPlanPeriodEnd(LocalDateTime.parse("2026-02-15T00:00:00"));

        when(userSubscriptionMapper.selectByUser("user_1")).thenReturn(subscription);
        when(userSubscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(subscription);
        when(userSubscriptionMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(userAiQuotaMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(taskQuota, detectionQuota, humanizerQuota))
                .thenReturn(List.of(taskQuota, detectionQuota, humanizerQuota));
        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAiQuotaMapper.updateById(any(UserAiQuotaEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        PlanQuotaServiceImpl service = new PlanQuotaServiceImpl(
                subscriptionPlanMapper,
                aiFeatureDefsMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        service.refreshAllPlanQuotasIfNeeded("user_1", LocalDateTime.parse("2026-03-20T12:00:00"));

        verify(userSubscriptionMapper, times(1)).selectByUser("user_1");
        verify(userSubscriptionMapper, times(1)).selectByUserForUpdate("user_1");
        verify(subscriptionPlanMapper, times(1)).selectOne(any(Wrapper.class));
        verify(userAiQuotaMapper, times(2)).selectList(any(Wrapper.class));
        verify(userAiQuotaMapper, times(3)).updateById(any(UserAiQuotaEntity.class));
        verify(quotaLedgerMapper, times(3)).insert(any(QuotaLedgerEntity.class));
    }

    @Test
    void refreshAllPlanQuotasIfNeeded_skipsLockWhenNoFeatureNeedsRefresh() {
        UserSubscriptionEntity subscription = subscription(
                "user_1",
                "pro_yearly",
                "active",
                LocalDateTime.parse("2026-01-15T00:00:00"),
                LocalDateTime.parse("2026-12-15T00:00:00"));

        UserAiQuotaEntity taskQuota = quota(11L, "task_create", 1L, 3L, 0L);
        taskQuota.setPlanPeriodStart(LocalDateTime.parse("2026-06-15T00:00:00"));
        taskQuota.setPlanPeriodEnd(LocalDateTime.parse("2026-07-15T00:00:00"));

        UserAiQuotaEntity detectionQuota = quota(12L, "ai_detection", 1L, 4L, 0L);
        detectionQuota.setPlanPeriodStart(LocalDateTime.parse("2026-06-15T00:00:00"));
        detectionQuota.setPlanPeriodEnd(LocalDateTime.parse("2026-07-15T00:00:00"));

        UserAiQuotaEntity humanizerQuota = quota(13L, "humanizer", 1L, 2L, 0L);
        humanizerQuota.setPlanPeriodStart(LocalDateTime.parse("2026-06-15T00:00:00"));
        humanizerQuota.setPlanPeriodEnd(LocalDateTime.parse("2026-07-15T00:00:00"));

        when(userSubscriptionMapper.selectByUser("user_1")).thenReturn(subscription);
        when(userAiQuotaMapper.selectList(any(Wrapper.class))).thenReturn(List.of(taskQuota, detectionQuota, humanizerQuota));

        PlanQuotaServiceImpl service = new PlanQuotaServiceImpl(
                subscriptionPlanMapper,
                aiFeatureDefsMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        service.refreshAllPlanQuotasIfNeeded("user_1", LocalDateTime.parse("2026-07-01T12:00:00"));

        verify(userSubscriptionMapper).selectByUser("user_1");
        verify(userSubscriptionMapper, never()).selectByUserForUpdate("user_1");
        verify(subscriptionPlanMapper, never()).selectOne(any(Wrapper.class));
        verify(userAiQuotaMapper, times(1)).selectList(any(Wrapper.class));
    }

    private SubscriptionPlanEntity plan(String code, long assignment, long detection, long humanizer) {
        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode(code);
        plan.setAssignmentQuota(assignment);
        plan.setDetectionQuota(detection);
        plan.setHumanizerQuota(humanizer);
        plan.setIsActive(true);
        return plan;
    }

    private UserAiQuotaEntity quota(Long id, String featureCode, long free, long plan, long paid) {
        UserAiQuotaEntity quota = new UserAiQuotaEntity();
        quota.setId(id);
        quota.setClerkUserId("user_1");
        quota.setFeatureCode(featureCode);
        quota.setFreeBalance(free);
        quota.setPlanBalance(plan);
        quota.setPaidBalance(paid);
        quota.setVersion(0);
        return quota;
    }

    private UserSubscriptionEntity subscription(
            String clerkUserId,
            String planCode,
            String status,
            LocalDateTime quotaPeriodStart,
            LocalDateTime quotaPeriodEnd) {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setClerkUserId(clerkUserId);
        subscription.setPlanCode(planCode);
        subscription.setStatus(status);
        subscription.setQuotaPeriodStart(quotaPeriodStart);
        subscription.setQuotaPeriodEnd(quotaPeriodEnd);
        return subscription;
    }

    @SuppressWarnings("unused")
    private AiFeatureDefsEntity featureDef(String featureCode, long freeAmount) {
        AiFeatureDefsEntity featureDef = new AiFeatureDefsEntity();
        featureDef.setFeatureCode(featureCode);
        featureDef.setFreeQuotaAmount(freeAmount);
        featureDef.setFreeQuotaPeriod("monthly");
        featureDef.setIsActive(true);
        return featureDef;
    }
}
