package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.QuotaLedgerEntity;
import com.studyagent.infra.entity.UserAddonGrantEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.QuotaLedgerMapper;
import com.studyagent.infra.mapper.UserAddonGrantMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddonGrantServiceImplTest {
    @Mock
    private AddonPackageDefMapper addonPackageDefMapper;
    @Mock
    private UserAddonGrantMapper userAddonGrantMapper;
    @Mock
    private QuotaLedgerMapper quotaLedgerMapper;
    @Mock
    private UserSubscriptionMapper userSubscriptionMapper;
    @Mock
    private QuotaGrantAnalyticsPublisher quotaGrantAnalyticsPublisher;

    @Test
    void grantFromPaidCheckout_isIdempotent_perStripeSession() {
        AddonPackageDefEntity addon = addon("addon_assignment_3", "task_create", 3L, 2);
        UserAddonGrantEntity existing = new UserAddonGrantEntity();
        existing.setId(9L);
        existing.setStripeSessionId("cs_1");

        when(addonPackageDefMapper.selectOne(any(Wrapper.class))).thenReturn(addon);
        when(userAddonGrantMapper.selectOne(any(Wrapper.class))).thenReturn(null, existing);
        when(userAddonGrantMapper.insert(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        AddonGrantServiceImpl service = new AddonGrantServiceImpl(
                addonPackageDefMapper,
                userAddonGrantMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);
        ReflectionTestUtils.setField(service, "quotaGrantAnalyticsPublisher", quotaGrantAnalyticsPublisher);

        Instant paidAt = Instant.parse("2026-06-15T08:30:00Z");
        service.grantFromPaidCheckout("user_1", "addon_assignment_3", "cs_1", "pi_1", paidAt);
        service.grantFromPaidCheckout("user_1", "addon_assignment_3", "cs_1", "pi_1", paidAt);

        ArgumentCaptor<UserAddonGrantEntity> grantCaptor = ArgumentCaptor.forClass(UserAddonGrantEntity.class);
        verify(userAddonGrantMapper, times(1)).insert(grantCaptor.capture());
        UserAddonGrantEntity inserted = grantCaptor.getValue();
        assertEquals("task_create", inserted.getFeatureCode());
        assertEquals("addon", inserted.getGrantType());
        assertEquals(3L, inserted.getInitialAmount());
        assertEquals(3L, inserted.getRemainingAmount());
        assertEquals("active", inserted.getStatus());
        assertEquals(DateTimeFormats.fromInstant(paidAt).plusMonths(2), inserted.getExpiresAt());

        ArgumentCaptor<QuotaLedgerEntity> ledgerCaptor = ArgumentCaptor.forClass(QuotaLedgerEntity.class);
        verify(quotaLedgerMapper).insert(ledgerCaptor.capture());
        QuotaLedgerEntity ledger = ledgerCaptor.getValue();
        assertNull(ledger.getInvoiceId());

        ArgumentCaptor<QuotaGrantAnalyticsEvent> analyticsCaptor = ArgumentCaptor.forClass(QuotaGrantAnalyticsEvent.class);
        verify(quotaGrantAnalyticsPublisher).publishAfterCommit(analyticsCaptor.capture());
        assertEquals("addon", analyticsCaptor.getValue().grantType());
        assertEquals("addon_assignment_3", analyticsCaptor.getValue().addonCode());
        assertEquals(3L, analyticsCaptor.getValue().quotaAmount());
    }

    @Test
    void pauseAll_marksActiveGrantsPaused() {
        UserAddonGrantEntity activeGrant = grant("active", LocalDateTime.now().plusDays(3), 2L);

        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of(activeGrant));
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        AddonGrantServiceImpl service = new AddonGrantServiceImpl(
                addonPackageDefMapper,
                userAddonGrantMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        service.pauseAll("user_1", "sub_1", "subscription:sub_1:pause-addons");

        ArgumentCaptor<UserAddonGrantEntity> grantCaptor = ArgumentCaptor.forClass(UserAddonGrantEntity.class);
        verify(userAddonGrantMapper).updateById(grantCaptor.capture());
        assertEquals("paused", grantCaptor.getValue().getStatus());
    }

    @Test
    void expireEligible_marksExpiredGrantsAndWritesVisibleLedger() {
        UserAddonGrantEntity expiredGrant = grant("active", LocalDateTime.now().minusDays(1), 2L);

        when(userAddonGrantMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(expiredGrant), List.of());
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        AddonGrantServiceImpl service = new AddonGrantServiceImpl(
                addonPackageDefMapper,
                userAddonGrantMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        service.expireEligible("user_1", "task_create", "balance_query");

        ArgumentCaptor<UserAddonGrantEntity> grantCaptor = ArgumentCaptor.forClass(UserAddonGrantEntity.class);
        verify(userAddonGrantMapper).updateById(grantCaptor.capture());
        assertEquals("expired", grantCaptor.getValue().getStatus());

        ArgumentCaptor<QuotaLedgerEntity> ledgerCaptor = ArgumentCaptor.forClass(QuotaLedgerEntity.class);
        verify(quotaLedgerMapper).insert(ledgerCaptor.capture());
        QuotaLedgerEntity ledger = ledgerCaptor.getValue();
        assertEquals("addon_expired", ledger.getLedgerType());
        assertEquals(-2L, ledger.getAmount());
        assertEquals("task_create", ledger.getFeatureCode());
        assertEquals("addon-expire:12", ledger.getIdempotencyKey());
    }

    @Test
    void expireEligible_usesSimulationTimeForTestClock() {
        LocalDateTime fallbackNow = LocalDateTime.parse("2026-07-05T10:00:00");
        LocalDateTime simulatedNow = fallbackNow.plusDays(2);
        UserAddonGrantEntity expiredInSimulation = grant("active", fallbackNow.plusDays(1), 2L);

        when(userAddonGrantMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(expiredInSimulation), List.of());
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        AddonGrantServiceImpl service = new AddonGrantServiceImpl(
                addonPackageDefMapper,
                userAddonGrantMapper,
                quotaLedgerMapper,
                userSubscriptionMapper) {
            @Override
            LocalDateTime resolveNow(String clerkUserId, LocalDateTime fallbackNowArg) {
                return simulatedNow;
            }
        };

        service.expireEligible("user_1", "task_create", "balance_query");

        ArgumentCaptor<QuotaLedgerEntity> ledgerCaptor = ArgumentCaptor.forClass(QuotaLedgerEntity.class);
        verify(quotaLedgerMapper).insert(ledgerCaptor.capture());
        assertEquals("addon_expired", ledgerCaptor.getValue().getLedgerType());
        assertEquals(simulatedNow, ledgerCaptor.getValue().getCreatedAt());
    }

    @Test
    void pauseAll_throws_whenGrantUpdateLosesRace() {
        UserAddonGrantEntity activeGrant = grant("active", LocalDateTime.now().plusDays(3), 2L);

        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of(activeGrant));
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(0);

        AddonGrantServiceImpl service = new AddonGrantServiceImpl(
                addonPackageDefMapper,
                userAddonGrantMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        assertThrows(IllegalStateException.class,
                () -> service.pauseAll("user_1", "sub_1", "subscription:sub_1:pause-addons"));
        verify(quotaLedgerMapper, never()).insert(any(QuotaLedgerEntity.class));
    }

    @Test
    void resumeEligible_reactivatesFutureGrants_andExpiresPastOnes() {
        UserAddonGrantEntity futureGrant = grant("paused", LocalDateTime.now().plusDays(3), 2L);
        UserAddonGrantEntity expiredGrant = grant("paused", LocalDateTime.now().minusDays(1), 1L);

        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAddonGrantMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(futureGrant, expiredGrant), List.of(futureGrant));
        when(userAddonGrantMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(userAddonGrantMapper.updateById(any(UserAddonGrantEntity.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        AddonGrantServiceImpl service = new AddonGrantServiceImpl(
                addonPackageDefMapper,
                userAddonGrantMapper,
                quotaLedgerMapper,
                userSubscriptionMapper);

        service.resumeEligible("user_1", "sub_1", "subscription:sub_1:resume-addons");

        verify(userAddonGrantMapper, times(1)).update(any(), any(Wrapper.class));
        verify(userAddonGrantMapper, times(1)).updateById(any(UserAddonGrantEntity.class));
        assertEquals("active", futureGrant.getStatus());
        assertNull(futureGrant.getPausedAt());
        assertEquals("expired", expiredGrant.getStatus());

        ArgumentCaptor<QuotaLedgerEntity> ledgerCaptor = ArgumentCaptor.forClass(QuotaLedgerEntity.class);
        verify(quotaLedgerMapper, times(2)).insert(ledgerCaptor.capture());
        List<QuotaLedgerEntity> ledgers = ledgerCaptor.getAllValues();
        assertEquals("addon_expired", ledgers.get(0).getLedgerType());
        assertEquals("addon_resume", ledgers.get(1).getLedgerType());
    }

    private AddonPackageDefEntity addon(String code, String featureCode, long amount, int validityMonths) {
        AddonPackageDefEntity addon = new AddonPackageDefEntity();
        addon.setAddonCode(code);
        addon.setFeatureCode(featureCode);
        addon.setQuotaAmount(amount);
        addon.setValidityMonths(validityMonths);
        addon.setIsActive(true);
        return addon;
    }

    private UserAddonGrantEntity grant(String status, LocalDateTime expiresAt, long remaining) {
        UserAddonGrantEntity grant = new UserAddonGrantEntity();
        grant.setId(10L + remaining);
        grant.setClerkUserId("user_1");
        grant.setFeatureCode("task_create");
        grant.setAddonCode("addon_assignment_3");
        grant.setStatus(status);
        grant.setGrantType("addon");
        grant.setInitialAmount(3L);
        grant.setRemainingAmount(remaining);
        grant.setExpiresAt(expiresAt);
        grant.setPausedAt(LocalDateTime.now().minusHours(1));
        return grant;
    }
}
