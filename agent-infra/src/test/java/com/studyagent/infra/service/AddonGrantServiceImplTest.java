package com.studyagent.infra.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.QuotaLedgerEntity;
import com.studyagent.infra.entity.UserAddonGrantEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.QuotaLedgerMapper;
import com.studyagent.infra.mapper.UserAddonGrantMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
                quotaLedgerMapper);

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
                quotaLedgerMapper);

        service.pauseAll("user_1", "sub_1", "subscription:sub_1:pause-addons");

        ArgumentCaptor<UserAddonGrantEntity> grantCaptor = ArgumentCaptor.forClass(UserAddonGrantEntity.class);
        verify(userAddonGrantMapper).updateById(grantCaptor.capture());
        assertEquals("paused", grantCaptor.getValue().getStatus());
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
                quotaLedgerMapper);

        assertThrows(IllegalStateException.class,
                () -> service.pauseAll("user_1", "sub_1", "subscription:sub_1:pause-addons"));
        verify(quotaLedgerMapper, never()).insert(any(QuotaLedgerEntity.class));
    }

    @Test
    void resumeEligible_reactivatesFutureGrants_andExpiresPastOnes() {
        UserAddonGrantEntity futureGrant = grant("paused", LocalDateTime.now().plusDays(3), 2L);
        UserAddonGrantEntity expiredGrant = grant("paused", LocalDateTime.now().minusDays(1), 1L);

        when(quotaLedgerMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userAddonGrantMapper.selectList(any(Wrapper.class))).thenReturn(List.of(futureGrant, expiredGrant));
        when(userAddonGrantMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedgerEntity.class))).thenReturn(1);

        AddonGrantServiceImpl service = new AddonGrantServiceImpl(
                addonPackageDefMapper,
                userAddonGrantMapper,
                quotaLedgerMapper);

        service.resumeEligible("user_1", "sub_1", "subscription:sub_1:resume-addons");

        verify(userAddonGrantMapper, times(2)).update(any(), any(Wrapper.class));
        verify(userAddonGrantMapper, never()).updateById(any(UserAddonGrantEntity.class));
        assertEquals("active", futureGrant.getStatus());
        assertNull(futureGrant.getPausedAt());
        assertEquals("expired", expiredGrant.getStatus());
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
