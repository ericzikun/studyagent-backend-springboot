package com.studyagent.infra.service.billing;

import com.studyagent.service.domain.billing.BillingPlan;
import com.studyagent.service.domain.billing.IntroTrialPlans;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntroTrialBillingTest {

    @Test
    void classifyPlanChange_trialToPlusIsImmediateUpgrade() {
        assertEquals(
                BillingDomainServiceImpl.PlanChangeAction.IMMEDIATE_UPGRADE,
                BillingDomainServiceImpl.classifyPlanChange(
                        IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY, "basic", "month",
                        "plus_monthly", "plus", "month"));
    }

    @Test
    void classifyPlanChange_trialToBasicIsUnsupported() {
        assertEquals(
                BillingDomainServiceImpl.PlanChangeAction.UNSUPPORTED,
                BillingDomainServiceImpl.classifyPlanChange(
                        IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY, "basic", "month",
                        IntroTrialPlans.CONVERSION_PLAN_CODE_MONTHLY, "basic", "month"));
    }

    @Test
    void classifyPlanChange_trialToYearlyBasicIsUnsupported() {
        assertEquals(
                BillingDomainServiceImpl.PlanChangeAction.UNSUPPORTED,
                BillingDomainServiceImpl.classifyPlanChange(
                        IntroTrialPlans.TRIAL_PLAN_CODE_YEARLY, "basic", "year",
                        IntroTrialPlans.CONVERSION_PLAN_CODE_YEARLY, "basic", "year"));
    }

    @Test
    void classifyPlanChange_paidToTrialIsUnsupported() {
        assertEquals(
                BillingDomainServiceImpl.PlanChangeAction.UNSUPPORTED,
                BillingDomainServiceImpl.classifyPlanChange(
                        "plus_monthly", "plus", "month",
                        IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY, "basic", "month"));
    }

    @Test
    void classifyPlanChange_trialAndBasicSameTierDoNotNoop() {
        // Critical: both have tier=basic + interval=month; must use plan codes.
        assertEquals(
                BillingDomainServiceImpl.PlanChangeAction.UNSUPPORTED,
                BillingDomainServiceImpl.classifyPlanChange(
                        IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY, "basic", "month",
                        "basic_monthly", "basic", "month"));
    }

    @Test
    void classifyPlanChange_basicToPlusStillImmediateUpgrade() {
        assertEquals(
                BillingDomainServiceImpl.PlanChangeAction.IMMEDIATE_UPGRADE,
                BillingDomainServiceImpl.classifyPlanChange(
                        "basic_monthly", "basic", "month",
                        "plus_monthly", "plus", "month"));
    }

    @Test
    void lapsedPlanBlocksEntitlementsButExposesFreeTier() {
        BillingPlan lapsed = BillingPlan.lapsedPlan();
        assertEquals("lapsed", lapsed.getPlanCode());
        assertEquals("free", lapsed.getTier());
        assertEquals(0, lapsed.getMaxFiles());
        assertEquals(0, lapsed.getMaxFollowupEdits());
        assertEquals(0L, lapsed.getAssignmentQuota());
        assertTrue(lapsed.isLapsedOrFree());
        assertFalse(IntroTrialPlans.isIntroTrialPlanCode("basic_monthly"));
        assertTrue(IntroTrialPlans.isIntroTrialPlanCode(IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY));
        assertTrue(IntroTrialPlans.isIntroTrialPlanCode(IntroTrialPlans.TRIAL_PLAN_CODE_YEARLY));
        assertTrue(IntroTrialPlans.isIntroTrialOfferKind(IntroTrialPlans.OFFER_KIND_BASIC_PAID_TRIAL));
        assertEquals(
                IntroTrialPlans.CONVERSION_PLAN_CODE_YEARLY,
                IntroTrialPlans.defaultConversionPlanCode(IntroTrialPlans.TRIAL_PLAN_CODE_YEARLY));
    }
}
