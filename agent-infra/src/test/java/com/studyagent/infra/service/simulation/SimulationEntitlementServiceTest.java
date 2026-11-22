package com.studyagent.infra.service.simulation;

import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.BillingPlan;
import com.studyagent.service.domain.billing.PaymentRecoveryAccess;
import com.studyagent.service.domain.billing.PaymentRecoveryAccessService;
import com.studyagent.service.domain.billing.SubscriptionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulationEntitlementServiceTest {

    private BillingDomainService billing;
    private SimulationPassService passService;
    private SimulationTrialGrantService trialGrantService;
    private PaymentRecoveryAccessService paymentRecoveryAccessService;
    private JdbcTemplate jdbcTemplate;
    private SimulationEntitlementService service;

    @BeforeEach
    void setUp() {
        billing = mock(BillingDomainService.class);
        passService = mock(SimulationPassService.class);
        trialGrantService = mock(SimulationTrialGrantService.class);
        paymentRecoveryAccessService = mock(PaymentRecoveryAccessService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new SimulationEntitlementService(
                billing, passService, trialGrantService, paymentRecoveryAccessService, jdbcTemplate);
        when(billing.isPaidMember(anyString())).thenReturn(false);
        when(billing.getCurrentSubscription(anyString())).thenReturn(null);
        when(paymentRecoveryAccessService.resolve(anyString(), any()))
                .thenReturn(PaymentRecoveryAccess.none());
        when(passService.access(anyString(), anyBoolean())).thenReturn(
                new SimulationPassService.Access(
                        false, false, true, "simulation_lab_7d", null, 199, "usd", 7, false));
        when(passService.hasActivePass(anyString())).thenReturn(false);
    }

    @Test
    void freeUserGetsOneLifetimeTrialWhenNoSuccessfulLabExists() {
        when(trialGrantService.isUsed("user_free")).thenReturn(false);

        SimulationEntitlementService.Entitlement entitlement = service.resolve("user_free");

        assertTrue(entitlement.canCreate());
        assertFalse(entitlement.previewLocked());
        assertEquals(SimulationEntitlementService.ENTITLEMENT_FREE_TRIAL, entitlement.entitlement());
        assertEquals(1L, entitlement.limit());
        assertEquals(0L, entitlement.used());
    }

    @Test
    void memberUsageCountsAgainstUtcPeriodBounds() {
        LocalDateTime startCst = LocalDateTime.of(2026, 11, 22, 17, 17, 26);
        LocalDateTime endCst = startCst.plusMonths(1);
        when(billing.isPaidMember("member")).thenReturn(true);
        when(billing.getEffectivePlanOrFree("member")).thenReturn(
                BillingPlan.builder().assignmentQuota(3L).build());
        when(billing.getCurrentSubscription("member")).thenReturn(
                SubscriptionResult.builder()
                        .quotaPeriodStart(startCst)
                        .quotaPeriodEnd(endCst)
                        .build());
        // Sessions are stored in UTC wall clock: 17:17 Shanghai == 09:17 UTC.
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), eq("member"),
                eq(startCst.minusHours(8)), eq(endCst.minusHours(8))))
                .thenReturn(3L);

        SimulationEntitlementService.Entitlement entitlement = service.resolve("member");

        assertEquals(3L, entitlement.used());
        assertEquals(0L, entitlement.remaining());
        assertFalse(entitlement.canCreate());
    }

    @Test
    void freeUserIsLockedAfterFirstSuccessfulLab() {
        when(trialGrantService.isUsed("user_free")).thenReturn(true);

        SimulationEntitlementService.Entitlement entitlement = service.resolve("user_free");

        assertFalse(entitlement.canCreate());
        assertTrue(entitlement.previewLocked());
        assertEquals(SimulationEntitlementService.ENTITLEMENT_FREE_TRIAL_USED, entitlement.entitlement());
    }

    @Test
    void memberQuotaFollowsAssignmentQuotaAndPeriodUsage() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 0, 0);
        LocalDateTime end = start.plusMonths(1);
        when(billing.isPaidMember("member")).thenReturn(true);
        when(billing.getEffectivePlanOrFree("member")).thenReturn(
                BillingPlan.builder().assignmentQuota(3L).build());
        when(billing.getCurrentSubscription("member")).thenReturn(
                SubscriptionResult.builder()
                        .quotaPeriodStart(start)
                        .quotaPeriodEnd(end)
                        .build());
        when(passService.access(eq("member"), eq(true))).thenReturn(
                new SimulationPassService.Access(
                        true, false, false, "simulation_lab_7d", null, 199, "usd", 7, true));
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1L);

        SimulationEntitlementService.Entitlement entitlement = service.resolve("member");

        assertTrue(entitlement.canCreate());
        assertFalse(entitlement.previewLocked());
        assertEquals(SimulationEntitlementService.ENTITLEMENT_MEMBERSHIP, entitlement.entitlement());
        assertEquals(3L, entitlement.limit());
        assertEquals(1L, entitlement.used());
        assertEquals(2L, entitlement.remaining());
    }

    @Test
    void activePassAllowsThreeGenerationsInItsWindow() {        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = start.plusDays(7);
        when(passService.hasActivePass("pass_user")).thenReturn(true);
        when(passService.activePassStartedAt("pass_user")).thenReturn(start);
        when(passService.access(eq("pass_user"), eq(false))).thenReturn(
                new SimulationPassService.Access(
                        true, false, true, "simulation_lab_7d",
                        end, 199, "usd", 7, false));
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1L);

        SimulationEntitlementService.Entitlement entitlement = service.resolve("pass_user");

        assertTrue(entitlement.canCreate());
        assertFalse(entitlement.unlimited());
        assertEquals(SimulationEntitlementService.ENTITLEMENT_PASS, entitlement.entitlement());
        assertEquals(3L, entitlement.limit());
        assertEquals(1L, entitlement.used());
        assertEquals(2L, entitlement.remaining());
    }

    @Test
    void membershipSupersedesAnExhaustedActivePass() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 0, 0);
        LocalDateTime end = start.plusMonths(1);
        when(billing.isPaidMember("member_with_pass")).thenReturn(true);
        when(billing.getEffectivePlanOrFree("member_with_pass")).thenReturn(
                BillingPlan.builder().assignmentQuota(16L).build());
        when(billing.getCurrentSubscription("member_with_pass")).thenReturn(
                SubscriptionResult.builder()
                        .quotaPeriodStart(start)
                        .quotaPeriodEnd(end)
                        .build());
        when(passService.hasActivePass("member_with_pass")).thenReturn(true);
        when(passService.activePassStartedAt("member_with_pass")).thenReturn(start.minusDays(1));
        when(passService.access(eq("member_with_pass"), eq(true))).thenReturn(
                new SimulationPassService.Access(
                        true, false, false, "simulation_lab_7d",
                        end, 199, "usd", 7, true));
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(3L);

        SimulationEntitlementService.Entitlement entitlement =
                service.resolve("member_with_pass");

        assertTrue(entitlement.canCreate());
        assertEquals(SimulationEntitlementService.ENTITLEMENT_MEMBERSHIP, entitlement.entitlement());
        assertEquals(16L, entitlement.limit());
        assertEquals(13L, entitlement.remaining());
    }

    @Test
    void paymentRecoveryLockBeatsActivePassAndStopsNewPassPurchase() {
        LocalDateTime passEnd = LocalDateTime.of(2026, 11, 20, 0, 0);
        when(passService.hasActivePass("user_locked")).thenReturn(true);
        when(passService.activePassStartedAt("user_locked")).thenReturn(passEnd.minusDays(7));
        when(passService.access(eq("user_locked"), anyBoolean())).thenReturn(
                new SimulationPassService.Access(
                        true, false, true, "simulation_lab_7d",
                        passEnd, 199, "usd", 7, false));
        when(paymentRecoveryAccessService.resolve(eq("user_locked"), any()))
                .thenReturn(new PaymentRecoveryAccess(true, false, null, null, null));

        SimulationEntitlementService.Entitlement entitlement = service.resolve("user_locked");

        assertFalse(entitlement.canCreate());
        assertTrue(entitlement.previewLocked());
        assertEquals(SimulationEntitlementService.ENTITLEMENT_PAYMENT_RECOVERY, entitlement.entitlement());
        assertFalse(entitlement.purchasable());
        assertFalse(entitlement.membershipIncluded());
    }

    @Test
    void paymentRecoveryLockAlsoBeatsActiveMembership() {
        when(billing.isPaidMember("member_locked")).thenReturn(true);
        when(paymentRecoveryAccessService.resolve(eq("member_locked"), any()))
                .thenReturn(new PaymentRecoveryAccess(true, false, null, null, null));

        SimulationEntitlementService.Entitlement entitlement = service.resolve("member_locked");

        assertFalse(entitlement.canCreate());
        assertEquals(SimulationEntitlementService.ENTITLEMENT_PAYMENT_RECOVERY, entitlement.entitlement());
    }

    @Test
    void adminKeepsIndependentAccessWhileRecoveryIsLocked() {
        when(passService.access(eq("user_admin"), anyBoolean())).thenReturn(
                new SimulationPassService.Access(
                        false, true, true, "simulation_lab_7d", null, 199, "usd", 7, false));
        when(paymentRecoveryAccessService.resolve(eq("user_admin"), any()))
                .thenReturn(new PaymentRecoveryAccess(true, false, null, null, null));

        SimulationEntitlementService.Entitlement entitlement = service.resolve("user_admin");

        assertTrue(entitlement.canCreate());
        assertEquals(SimulationEntitlementService.ENTITLEMENT_ADMIN, entitlement.entitlement());
    }
}
