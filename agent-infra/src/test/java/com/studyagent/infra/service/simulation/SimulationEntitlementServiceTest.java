package com.studyagent.infra.service.simulation;

import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.BillingPlan;
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
    private JdbcTemplate jdbcTemplate;
    private SimulationEntitlementService service;

    @BeforeEach
    void setUp() {
        billing = mock(BillingDomainService.class);
        passService = mock(SimulationPassService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new SimulationEntitlementService(billing, passService, jdbcTemplate);
        when(billing.isPaidMember(anyString())).thenReturn(false);
        when(billing.getCurrentSubscription(anyString())).thenReturn(null);
        when(passService.access(anyString(), anyBoolean())).thenReturn(
                new SimulationPassService.Access(
                        false, false, true, "simulation_lab_7d", null, 199, "usd", 7, false));
        when(passService.hasActivePass(anyString())).thenReturn(false);
    }

    @Test
    void freeUserGetsOneLifetimeTrialWhenNoSuccessfulLabExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenReturn(0L);

        SimulationEntitlementService.Entitlement entitlement = service.resolve("user_free");

        assertTrue(entitlement.canCreate());
        assertFalse(entitlement.previewLocked());
        assertEquals(SimulationEntitlementService.ENTITLEMENT_FREE_TRIAL, entitlement.entitlement());
        assertEquals(1L, entitlement.limit());
        assertEquals(0L, entitlement.used());
    }

    @Test
    void freeUserIsLockedAfterFirstSuccessfulLab() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenReturn(1L);

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
    void activePassIsUnlimited() {
        when(passService.hasActivePass("pass_user")).thenReturn(true);
        when(passService.access(eq("pass_user"), eq(false))).thenReturn(
                new SimulationPassService.Access(
                        true, false, true, "simulation_lab_7d",
                        LocalDateTime.now().plusDays(2), 199, "usd", 7, false));

        SimulationEntitlementService.Entitlement entitlement = service.resolve("pass_user");

        assertTrue(entitlement.canCreate());
        assertTrue(entitlement.unlimited());
        assertEquals(SimulationEntitlementService.ENTITLEMENT_PASS, entitlement.entitlement());
    }
}
