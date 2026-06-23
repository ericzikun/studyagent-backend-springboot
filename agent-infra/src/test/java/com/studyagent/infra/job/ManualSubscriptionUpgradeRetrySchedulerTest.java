package com.studyagent.infra.job;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.service.billing.StripeBillingWebhookService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualSubscriptionUpgradeRetrySchedulerTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisPlusTableInfoTestHelper.initTableInfo(RechargeOrderEntity.class);
    }

    @Mock
    private RechargeOrderMapper rechargeOrderMapper;
    @Mock
    private StripeBillingWebhookService stripeBillingWebhookService;

    @Test
    void retryFailedManualUpgradeSwitches_retriesEachFailedManualUpgradeOrder() {
        RechargeOrderEntity failedOrder = new RechargeOrderEntity();
        failedOrder.setOrderNo("RO202606230001");
        failedOrder.setUpdatedAt(LocalDateTime.now().minusMinutes(20));

        when(rechargeOrderMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(failedOrder))
                .thenReturn(List.of());

        ManualSubscriptionUpgradeRetryScheduler scheduler =
                new ManualSubscriptionUpgradeRetryScheduler(rechargeOrderMapper, stripeBillingWebhookService);
        ReflectionTestUtils.setField(scheduler, "retryEnabled", true);
        ReflectionTestUtils.setField(scheduler, "retryAfterMinutes", 10);
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);

        scheduler.retryFailedManualUpgradeSwitches();

        verify(stripeBillingWebhookService).retryManualUpgradeSwitch("RO202606230001");
    }
}
