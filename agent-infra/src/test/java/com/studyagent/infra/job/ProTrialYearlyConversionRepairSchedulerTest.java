package com.studyagent.infra.job;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper;
import com.studyagent.service.domain.billing.BillingDomainService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProTrialYearlyConversionRepairSchedulerTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisPlusTableInfoTestHelper.initTableInfo(UserSubscriptionEntity.class);
    }

    @Mock
    private UserSubscriptionMapper subscriptionMapper;
    @Mock
    private BillingDomainService billingDomainService;

    @Test
    void repointsYearlyTrialSubscriptionToMonthlyConversion() {
        UserSubscriptionEntity row = yearlyTrial(11L, "user_1");
        when(subscriptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        when(subscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        scheduler(true).repointYearlyTrialConversions();

        verify(billingDomainService).fulfillIntroTrialSubscription("user_1", "cus_1", "sub_1");
    }

    @Test
    void skipsSubscriptionAlreadyPointingAtMonthlyConversion() {
        UserSubscriptionEntity row = yearlyTrial(12L, "user_2");
        when(subscriptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        when(subscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        scheduler(true).repointYearlyTrialConversions();

        verify(billingDomainService, never()).fulfillIntroTrialSubscription(any(), any(), any());
    }

    @Test
    void doesNothingWhenDisabled() {
        scheduler(false).repointYearlyTrialConversions();

        verify(subscriptionMapper, never()).selectList(any(Wrapper.class));
        verify(billingDomainService, never()).fulfillIntroTrialSubscription(any(), any(), any());
    }

    @Test
    void keepsProcessingBatchWhenOneSubscriptionFails() {
        UserSubscriptionEntity failing = yearlyTrial(13L, "user_bad");
        UserSubscriptionEntity healthy = yearlyTrial(14L, "user_ok");
        when(subscriptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(failing, healthy));
        when(subscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        Mockito.doThrow(new IllegalStateException("stripe unavailable"))
                .when(billingDomainService)
                .fulfillIntroTrialSubscription("user_bad", "cus_1", "sub_1");

        scheduler(true).repointYearlyTrialConversions();

        verify(billingDomainService).fulfillIntroTrialSubscription("user_ok", "cus_1", "sub_1");
    }

    private ProTrialYearlyConversionRepairScheduler scheduler(boolean enabled) {
        ProTrialYearlyConversionRepairScheduler scheduler =
                new ProTrialYearlyConversionRepairScheduler(subscriptionMapper, billingDomainService);
        ReflectionTestUtils.setField(scheduler, "repairEnabled", enabled);
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);
        return scheduler;
    }

    private UserSubscriptionEntity yearlyTrial(long id, String clerkUserId) {
        UserSubscriptionEntity row = new UserSubscriptionEntity();
        row.setId(id);
        row.setClerkUserId(clerkUserId);
        row.setPlanCode("pro_trial_to_yearly");
        row.setStatus("active");
        row.setStripeCustomerId("cus_1");
        row.setStripeSubscriptionId("sub_1");
        row.setPendingPlanCode("pro_yearly");
        return row;
    }
}
