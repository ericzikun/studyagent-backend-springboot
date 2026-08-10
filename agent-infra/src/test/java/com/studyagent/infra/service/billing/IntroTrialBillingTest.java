package com.studyagent.infra.service.billing;

import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.service.domain.billing.BillingPlan;
import com.studyagent.service.domain.billing.IntroTrialPlans;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void quoteTrialUpgradeChargesFullTargetPriceWithoutCreditingTrialFee() {
        SubscriptionPlanEntity trial = plan(
                IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY,
                "basic",
                "month",
                299);
        SubscriptionPlanEntity plusMonthly = plan("plus_monthly", "plus", "month", 3999);
        SubscriptionPlanEntity plusYearly = plan("plus_yearly", "plus", "year", 19188);
        LocalDateTime start = LocalDateTime.parse("2026-08-01T00:00:00");
        LocalDateTime end = LocalDateTime.parse("2026-08-08T00:00:00");
        LocalDateTime now = LocalDateTime.parse("2026-08-03T00:00:00");

        UpgradeChargeQuote monthlyQuote = UpgradeChargeCalculator.quote(
                trial, plusMonthly, start, end, now, 299, "in_trial");
        UpgradeChargeQuote yearlyQuote = UpgradeChargeCalculator.quote(
                trial, plusYearly, start, end, now, 299, "in_trial");

        assertEquals(3999, monthlyQuote.getAmountCents());
        assertEquals("target_monthly_full", monthlyQuote.getPricingFormula());
        assertEquals(19188, yearlyQuote.getAmountCents());
        assertEquals("target_annual_full", yearlyQuote.getPricingFormula());
    }

    @Test
    void lapsedPlanBlocksEntitlementsButExposesFreeTier() {
        BillingPlan lapsed = BillingPlan.lapsedPlan();
        assertEquals("lapsed", lapsed.getPlanCode());
        assertEquals("free", lapsed.getTier());
        assertEquals(3, lapsed.getMaxFiles());
        assertEquals(0, lapsed.getMaxFollowupEdits());
        assertEquals(0L, lapsed.getAssignmentQuota());
        assertTrue(lapsed.isLapsedOrFree());
        assertFalse(IntroTrialPlans.isIntroTrialPlanCode("basic_monthly"));
        assertTrue(IntroTrialPlans.isIntroTrialPlanCode(IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY));
        assertTrue(IntroTrialPlans.isIntroTrialPlanCode(IntroTrialPlans.TRIAL_PLAN_CODE_YEARLY));
        assertTrue(IntroTrialPlans.isIntroTrialPlanCode(IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY));
        assertTrue(IntroTrialPlans.isIntroTrialPlanCode(IntroTrialPlans.PRO_TRIAL_PLAN_CODE_YEARLY));
        assertTrue(IntroTrialPlans.isIntroTrialPlanCode(IntroTrialPlans.PRO_TRIAL_ONCE_PLAN_CODE));
        assertFalse(IntroTrialPlans.isSellableIntroTrialPlanCode(IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY));
        assertFalse(IntroTrialPlans.isSellableIntroTrialPlanCode(IntroTrialPlans.TRIAL_PLAN_CODE_YEARLY));
        assertFalse(IntroTrialPlans.isSellableIntroTrialPlanCode("basic_trial_weekly"));
        assertFalse(IntroTrialPlans.isSellableIntroTrialPlanCode(IntroTrialPlans.PRO_TRIAL_ONCE_PLAN_CODE));
        assertTrue(IntroTrialPlans.isSellableIntroTrialPlanCode(IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY));
        assertTrue(IntroTrialPlans.isSellableIntroTrialPlanCode(IntroTrialPlans.PRO_TRIAL_PLAN_CODE_YEARLY));
        assertTrue(IntroTrialPlans.isIntroTrialOfferKind(IntroTrialPlans.OFFER_KIND_BASIC_PAID_TRIAL));
        assertTrue(IntroTrialPlans.isIntroTrialOfferKind(IntroTrialPlans.OFFER_KIND_PRO_PAID_TRIAL));
        assertTrue(IntroTrialPlans.isOneTimeProTrialPlan(
                IntroTrialPlans.PRO_TRIAL_ONCE_PLAN_CODE, IntroTrialPlans.OFFER_KIND_PRO_PAID_TRIAL));
        // Critical: subscription Pro Trial shares offer_kind but must NOT be treated as one-time.
        assertFalse(IntroTrialPlans.isOneTimeProTrialPlan(
                IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY, IntroTrialPlans.OFFER_KIND_PRO_PAID_TRIAL));
        assertNull(IntroTrialPlans.defaultConversionPlanCode(IntroTrialPlans.PRO_TRIAL_ONCE_PLAN_CODE));
        assertEquals(
                IntroTrialPlans.PRO_CONVERSION_PLAN_CODE_MONTHLY,
                IntroTrialPlans.defaultConversionPlanCode(IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY));
        assertEquals(
                IntroTrialPlans.PRO_CONVERSION_PLAN_CODE_YEARLY,
                IntroTrialPlans.defaultConversionPlanCode(IntroTrialPlans.PRO_TRIAL_PLAN_CODE_YEARLY));
        assertEquals(
                IntroTrialPlans.CONVERSION_PLAN_CODE_YEARLY,
                IntroTrialPlans.defaultConversionPlanCode(IntroTrialPlans.TRIAL_PLAN_CODE_YEARLY));
    }

    @Test
    void classifyPlanChange_proSubscriptionTrialToProMonthlyIsUnsupported() {
        assertEquals(
                BillingDomainServiceImpl.PlanChangeAction.UNSUPPORTED,
                BillingDomainServiceImpl.classifyPlanChange(
                        IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY, "pro", "month",
                        IntroTrialPlans.PRO_CONVERSION_PLAN_CODE_MONTHLY, "pro", "month"));
    }

    @Test
    void classifyPlanChange_historicalOneTimeProTrialToProMonthlyIsImmediateUpgrade() {
        assertEquals(
                BillingDomainServiceImpl.PlanChangeAction.IMMEDIATE_UPGRADE,
                BillingDomainServiceImpl.classifyPlanChange(
                        IntroTrialPlans.PRO_TRIAL_ONCE_PLAN_CODE, "pro", "once",
                        "pro_monthly", "pro", "month"));
    }

    @Test
    void sanitizeConversionPlanCode_rejectsTrialSkuAsTarget() {
        assertEquals(
                IntroTrialPlans.CONVERSION_PLAN_CODE_MONTHLY,
                IntroTrialPlans.sanitizeConversionPlanCode(
                        IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY,
                        IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY));
        assertEquals(
                IntroTrialPlans.CONVERSION_PLAN_CODE_YEARLY,
                IntroTrialPlans.sanitizeConversionPlanCode(
                        IntroTrialPlans.TRIAL_PLAN_CODE_YEARLY,
                        IntroTrialPlans.TRIAL_PLAN_CODE_YEARLY));
        assertEquals(
                IntroTrialPlans.CONVERSION_PLAN_CODE_MONTHLY,
                IntroTrialPlans.sanitizeConversionPlanCode(
                        IntroTrialPlans.CONVERSION_PLAN_CODE_MONTHLY,
                        IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY));
        assertEquals(
                IntroTrialPlans.PRO_CONVERSION_PLAN_CODE_MONTHLY,
                IntroTrialPlans.sanitizeConversionPlanCode(
                        IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY,
                        IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY));
        assertEquals(
                IntroTrialPlans.PRO_CONVERSION_PLAN_CODE_YEARLY,
                IntroTrialPlans.sanitizeConversionPlanCode(
                        IntroTrialPlans.PRO_CONVERSION_PLAN_CODE_YEARLY,
                        IntroTrialPlans.PRO_TRIAL_PLAN_CODE_YEARLY));
    }

    private static SubscriptionPlanEntity plan(
            String planCode,
            String tier,
            String interval,
            int priceCents) {
        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode(planCode);
        plan.setTier(tier);
        plan.setBillingInterval(interval);
        plan.setPriceCents(priceCents);
        return plan;
    }
}
