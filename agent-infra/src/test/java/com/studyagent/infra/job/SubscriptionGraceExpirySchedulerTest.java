package com.studyagent.infra.job;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.billing.BillingQuotaGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SubscriptionGraceExpirySchedulerTest {
    @Mock
    private UserSubscriptionMapper subscriptionMapper;
    @Mock
    private BillingQuotaGateway quotaGateway;

    @Test
    void expiredGraceClearsPlanPausesAddonsAndSuspendsSubscription() {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(1L);
        subscription.setClerkUserId("user_1");
        subscription.setStripeSubscriptionId("sub_1");
        subscription.setPlanCode("plus_monthly");
        subscription.setStatus("past_due");
        subscription.setGraceEndAt(LocalDateTime.now().minusMinutes(1));
        when(subscriptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(subscription));
        when(subscriptionMapper.selectByUserForUpdate("user_1")).thenReturn(subscription);

        SubscriptionGraceExpiryScheduler scheduler =
                new SubscriptionGraceExpiryScheduler(subscriptionMapper, quotaGateway);
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);

        scheduler.suspendExpiredGraceSubscriptions();

        verify(quotaGateway).clearPlanQuota(
                org.mockito.ArgumentMatchers.eq("user_1"),
                org.mockito.ArgumentMatchers.eq("sub_1"),
                org.mockito.ArgumentMatchers.eq("plus_monthly"),
                org.mockito.ArgumentMatchers.contains(":plan"));
        verify(quotaGateway).pauseAddons(
                org.mockito.ArgumentMatchers.eq("user_1"),
                org.mockito.ArgumentMatchers.eq("sub_1"),
                org.mockito.ArgumentMatchers.contains(":addons"));
        verify(subscriptionMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void subscriptionRecoveredBeforeRowLockSkipsQuotaSuspension() {
        UserSubscriptionEntity candidate = new UserSubscriptionEntity();
        candidate.setId(2L);
        candidate.setClerkUserId("user_2");
        candidate.setStripeSubscriptionId("sub_2");
        candidate.setPlanCode("plus_monthly");
        candidate.setStatus("past_due");
        candidate.setGraceEndAt(LocalDateTime.now().minusMinutes(1));

        UserSubscriptionEntity recovered = new UserSubscriptionEntity();
        recovered.setId(2L);
        recovered.setClerkUserId("user_2");
        recovered.setStripeSubscriptionId("sub_2");
        recovered.setPlanCode("plus_monthly");
        recovered.setStatus("active");
        recovered.setGraceEndAt(null);

        when(subscriptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(candidate));
        when(subscriptionMapper.selectByUserForUpdate("user_2")).thenReturn(recovered);

        SubscriptionGraceExpiryScheduler scheduler =
                new SubscriptionGraceExpiryScheduler(subscriptionMapper, quotaGateway);
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);

        scheduler.suspendExpiredGraceSubscriptions();

        verify(quotaGateway, never()).clearPlanQuota(any(), any(), any(), any());
        verify(quotaGateway, never()).pauseAddons(any(), any(), any());
        verify(subscriptionMapper, never()).update(isNull(), any(Wrapper.class));
    }
}
