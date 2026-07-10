package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.infra.entity.QuotaLedgerEntity;
import com.studyagent.infra.mapper.AiFeatureDefsMapper;
import com.studyagent.infra.mapper.AiFeaturePackageMapper;
import com.studyagent.infra.mapper.QuotaLedgerMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.UserAiQuotaMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotaRechargeServiceTest {
    @Mock
    private RechargeOrderMapper rechargeOrderMapper;
    @Mock
    private UserAiQuotaMapper userAiQuotaMapper;
    @Mock
    private QuotaLedgerMapper quotaLedgerMapper;
    @Mock
    private AiFeaturePackageMapper aiFeaturePackageMapper;
    @Mock
    private AiFeatureDefsMapper aiFeatureDefsMapper;
    @Mock
    private QuotaGrantAnalyticsPublisher quotaGrantAnalyticsPublisher;

    @Test
    void processRecharge_publishesAddonGrantOnlyAfterNewLedgerIsWritten() {
        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(rechargeOrderMapper.insert(any())).thenReturn(1);
        when(userAiQuotaMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAiQuotaMapper.insert(any())).thenReturn(1);
        when(aiFeatureDefsMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        QuotaRechargeService service = new QuotaRechargeService(
                rechargeOrderMapper,
                userAiQuotaMapper,
                quotaLedgerMapper,
                aiFeaturePackageMapper,
                aiFeatureDefsMapper);
        ReflectionTestUtils.setField(service, "quotaGrantAnalyticsPublisher", quotaGrantAnalyticsPublisher);

        boolean processed = service.processRecharge(
                "user_1", "humanizer", "humanizer_5000", 5_000L,
                699, "usd", "cs_1", "pi_1");

        assertTrue(processed);
        ArgumentCaptor<QuotaGrantAnalyticsEvent> analyticsCaptor = ArgumentCaptor.forClass(QuotaGrantAnalyticsEvent.class);
        verify(quotaGrantAnalyticsPublisher).publishAfterCommit(analyticsCaptor.capture());
        assertEquals("addon", analyticsCaptor.getValue().grantType());
        assertEquals("humanizer_5000", analyticsCaptor.getValue().addonCode());
        assertEquals("humanizer", analyticsCaptor.getValue().featureCode());
        assertEquals(5_000L, analyticsCaptor.getValue().quotaAmount());
    }
}
