package com.studyagent.infra.job;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingSubscriptionUpgradeCleanupSchedulerTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisPlusTableInfoTestHelper.initTableInfo(RechargeOrderEntity.class);
        MybatisPlusTableInfoTestHelper.initTableInfo(UserSubscriptionEntity.class);
    }

    @Mock
    private RechargeOrderMapper rechargeOrderMapper;
    @Mock
    private UserSubscriptionMapper userSubscriptionMapper;

    @Test
    void cleanupStalePendingUpgrades_expiresOrdersAndClearsPendingUpgradeFlag() {
        RechargeOrderEntity staleOrder = new RechargeOrderEntity();
        staleOrder.setId(1L);
        staleOrder.setClerkUserId("user_1");
        staleOrder.setPlanCode("pro_monthly");
        staleOrder.setCreatedAt(LocalDateTime.now().minusHours(30));

        when(rechargeOrderMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(staleOrder))
                .thenReturn(List.of());
        when(rechargeOrderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(rechargeOrderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        PendingSubscriptionUpgradeCleanupScheduler scheduler =
                new PendingSubscriptionUpgradeCleanupScheduler(rechargeOrderMapper, userSubscriptionMapper);
        ReflectionTestUtils.setField(scheduler, "cleanupEnabled", true);
        ReflectionTestUtils.setField(scheduler, "retentionHours", 24);
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);

        scheduler.cleanupStalePendingUpgrades();

        verify(rechargeOrderMapper).update(isNull(), any(Wrapper.class));
        verify(userSubscriptionMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void cleanupStalePendingUpgrades_keepsPendingFlagWhenAnotherUpgradeIsStillPending() {
        RechargeOrderEntity staleOrder = new RechargeOrderEntity();
        staleOrder.setId(1L);
        staleOrder.setClerkUserId("user_1");
        staleOrder.setPlanCode("pro_monthly");
        staleOrder.setCreatedAt(LocalDateTime.now().minusHours(30));

        when(rechargeOrderMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(staleOrder))
                .thenReturn(List.of());
        when(rechargeOrderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(rechargeOrderMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        PendingSubscriptionUpgradeCleanupScheduler scheduler =
                new PendingSubscriptionUpgradeCleanupScheduler(rechargeOrderMapper, userSubscriptionMapper);
        ReflectionTestUtils.setField(scheduler, "cleanupEnabled", true);
        ReflectionTestUtils.setField(scheduler, "retentionHours", 24);
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);

        scheduler.cleanupStalePendingUpgrades();

        verify(rechargeOrderMapper).update(isNull(), any(Wrapper.class));
        verify(userSubscriptionMapper, never()).update(isNull(), any(Wrapper.class));
    }
}
