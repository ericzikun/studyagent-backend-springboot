package com.studyagent.infra.job;

import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.quota.PlanQuotaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanQuotaRefreshSchedulerTest {

    @Mock
    private UserSubscriptionMapper userSubscriptionMapper;
    @Mock
    private PlanQuotaService planQuotaService;

    @Test
    void refreshDueAnnualPlanQuotas_callsBulkPlanRefreshOncePerUser() {
        UserSubscriptionEntity first = new UserSubscriptionEntity();
        first.setClerkUserId("user_1");
        first.setPlanCode("pro_yearly");
        first.setStatus("active");

        when(userSubscriptionMapper.selectAnnualSubscriptionsDueForPlanRefresh(100))
                .thenReturn(List.of(first));

        PlanQuotaRefreshScheduler scheduler = new PlanQuotaRefreshScheduler(userSubscriptionMapper, planQuotaService);
        ReflectionTestUtils.setField(scheduler, "refreshEnabled", true);
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);

        scheduler.refreshDuePlanQuotas();

        verify(planQuotaService, times(1)).refreshAllPlanQuotasIfNeeded("user_1");
        verify(userSubscriptionMapper).selectAnnualSubscriptionsDueForPlanRefresh(100);
    }

    @Test
    void refreshDueAnnualPlanQuotas_skipsWhenDisabled() {
        PlanQuotaRefreshScheduler scheduler = new PlanQuotaRefreshScheduler(userSubscriptionMapper, planQuotaService);
        ReflectionTestUtils.setField(scheduler, "refreshEnabled", false);
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);

        scheduler.refreshDuePlanQuotas();

        verify(userSubscriptionMapper, never()).selectAnnualSubscriptionsDueForPlanRefresh(100);
        verifyNoInteractions(planQuotaService);
    }
}
