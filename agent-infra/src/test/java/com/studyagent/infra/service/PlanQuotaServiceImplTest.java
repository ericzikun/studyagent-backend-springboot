package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.infra.entity.AiFeatureDefsEntity;
import com.studyagent.infra.entity.QuotaLedgerEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.entity.UserAiQuotaEntity;
import com.studyagent.infra.mapper.AiFeatureDefsMapper;
import com.studyagent.infra.mapper.QuotaLedgerMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.UserAiQuotaMapper;
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
                quotaLedgerMapper);

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
                quotaLedgerMapper);

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
                quotaLedgerMapper);

        service.clearPlanQuota("user_1", "sub_1", "subscription:sub_1:deleted");
        service.clearPlanQuota("user_1", "sub_1", "subscription:sub_1:deleted");

        verify(userAiQuotaMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(userAiQuotaMapper, never()).updateById(any(UserAiQuotaEntity.class));
        assertEquals(0L, assignment.getPlanBalance());
        assertNull(assignment.getPlanPeriodStart());
        assertNull(assignment.getPlanPeriodEnd());
        assertEquals(0L, detection.getPlanBalance());
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
