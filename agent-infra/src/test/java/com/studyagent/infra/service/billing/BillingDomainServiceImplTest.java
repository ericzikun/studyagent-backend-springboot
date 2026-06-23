package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper;
import com.stripe.model.Invoice;
import com.stripe.model.SubscriptionItem;
import com.stripe.param.SubscriptionScheduleUpdateParams;
import com.stripe.param.SubscriptionUpdateParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingDomainServiceImplTest {
    @BeforeAll
    static void initTableInfo() {
        MybatisPlusTableInfoTestHelper.initTableInfo(RechargeOrderEntity.class);
        MybatisPlusTableInfoTestHelper.initTableInfo(UserSubscriptionEntity.class);
    }

    @Mock
    private SubscriptionPlanMapper subscriptionPlanMapper;
    @Mock
    private AddonPackageDefMapper addonPackageDefMapper;
    @Mock
    private UserSubscriptionMapper userSubscriptionMapper;
    @Mock
    private RechargeOrderMapper rechargeOrderMapper;

    @Test
    void getCatalogMapsActivePlansAndAddons() {
        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode("basic_monthly");
        plan.setTier("basic");
        plan.setBillingInterval("month");
        plan.setStripePriceId("price_basic");
        plan.setPriceCents(1999);
        plan.setCurrency("usd");
        plan.setAssignmentQuota(3L);
        plan.setDetectionQuota(3L);
        plan.setHumanizerQuota(2L);

        AddonPackageDefEntity addon = new AddonPackageDefEntity();
        addon.setAddonCode("addon_assignment_3");
        addon.setFeatureCode("task_create");
        addon.setStripePriceId("price_addon");
        addon.setQuotaAmount(3L);
        addon.setValidityMonths(2);
        addon.setPriceCents(999);
        addon.setCurrency("usd");

        when(subscriptionPlanMapper.selectList(any(Wrapper.class))).thenReturn(List.of(plan));
        when(addonPackageDefMapper.selectList(any(Wrapper.class))).thenReturn(List.of(addon));

        BillingDomainServiceImpl service = service();
        var result = service.getCatalog();

        assertEquals("basic_monthly", result.getPlans().get(0).getPlanCode());
        assertEquals(3L, result.getPlans().get(0).getAssignmentQuota());
        assertEquals("time", result.getPlans().get(0).getAssignmentQuotaUnit());
        assertEquals("words", result.getPlans().get(0).getDetectionQuotaUnit());
        assertEquals("words", result.getPlans().get(0).getHumanizerQuotaUnit());
        assertEquals("addon_assignment_3", result.getAddons().get(0).getAddonCode());
        assertEquals(2, result.getAddons().get(0).getValidityMonths());
        assertEquals("time", result.getAddons().get(0).getQuotaUnit());
    }

    @Test
    void isPaidMemberOnlyAcceptsEffectivePaidStatuses() {
        UserSubscriptionEntity active = new UserSubscriptionEntity();
        active.setStripeSubscriptionId("sub_123");
        active.setStatus("active");
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(active);

        BillingDomainServiceImpl service = service();
        assertTrue(service.isPaidMember("user_1"));

        active.setStatus("past_due");
        assertFalse(service.isPaidMember("user_1"));
    }

    @Test
    void getEffectivePlanOrFreeReturnsActualPlanForActiveSubscription() {
        UserSubscriptionEntity active = new UserSubscriptionEntity();
        active.setStatus("active");
        active.setPlanCode("pro_monthly");

        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode("pro_monthly");
        plan.setTier("pro");
        plan.setBillingInterval("month");
        plan.setStripePriceId("price_pro_monthly");
        plan.setAssignmentQuota(20L);
        plan.setDetectionQuota(10L);
        plan.setHumanizerQuota(5L);
        plan.setMaxFiles(12);
        plan.setMaxFollowupEdits(8);
        plan.setAllowedOutputTypes("[\"writing\",\"ppt\"]");

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(active);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);

        BillingDomainServiceImpl service = service();
        var result = service.getEffectivePlanOrFree("user_1");

        assertEquals("pro_monthly", result.getPlanCode());
        assertEquals("pro", result.getTier());
        assertEquals("[\"writing\",\"ppt\"]", result.getAllowedOutputTypes());
        assertEquals(12, result.getMaxFiles());
    }

    @Test
    void getEffectivePlanOrFreeReturnsActualPlanForTrialingSubscription() {
        UserSubscriptionEntity active = new UserSubscriptionEntity();
        active.setStatus("trialing");
        active.setPlanCode("plus_legacy");

        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode("plus_legacy");
        plan.setTier("plus");
        plan.setBillingInterval("month");
        plan.setIsActive(false);
        plan.setStripePriceId(null);
        plan.setAssignmentQuota(8L);
        plan.setDetectionQuota(4L);
        plan.setHumanizerQuota(2L);
        plan.setMaxFiles(6);
        plan.setMaxFollowupEdits(5);
        plan.setAllowedOutputTypes("[\"writing\",\"slides\"]");

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(active);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);

        BillingDomainServiceImpl service = service();
        var result = service.getEffectivePlanOrFree("user_trialing");

        assertEquals("plus_legacy", result.getPlanCode());
        assertEquals("plus", result.getTier());
        assertEquals("[\"writing\",\"slides\"]", result.getAllowedOutputTypes());
        assertEquals(6, result.getMaxFiles());
    }

    @Test
    void getEffectivePlanOrFreeReturnsSyntheticFreePlanWhenNoActiveSubscription() {
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BillingDomainServiceImpl service = service();
        var result = service.getEffectivePlanOrFree("user_2");

        assertEquals("free", result.getPlanCode());
        assertEquals("free", result.getTier());
        assertEquals("none", result.getBillingInterval());
        assertEquals(1L, result.getAssignmentQuota());
        assertEquals(3000L, result.getDetectionQuota());
        assertEquals(1000L, result.getHumanizerQuota());
        assertEquals("words", result.getDetectionQuotaUnit());
        assertEquals("words", result.getHumanizerQuotaUnit());
        assertEquals(3, result.getMaxFiles());
        assertEquals(3, result.getMaxFollowupEdits());
        assertEquals("[\"writing\"]", result.getAllowedOutputTypes());
    }

    @Test
    void getEffectivePlanOrFreeReturnsSyntheticFreePlanForPastDueSubscription() {
        UserSubscriptionEntity active = new UserSubscriptionEntity();
        active.setStatus("past_due");
        active.setPlanCode("pro_monthly");
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(active);

        BillingDomainServiceImpl service = service();
        var result = service.getEffectivePlanOrFree("user_3");

        assertEquals("free", result.getPlanCode());
        assertEquals("free", result.getTier());
        assertEquals(3, result.getMaxFiles());
    }

    @Test
    void resolveCheckoutSuccessUrl_appendsResumeToken() {
        BillingDomainServiceImpl service = service();

        assertEquals(
                "http://localhost:3001/payment-success?resumeToken=resume_tok_1&session_id={CHECKOUT_SESSION_ID}",
                service.resolveCheckoutSuccessUrl("http://localhost:3001/payment-success", "resume_tok_1"));
        assertEquals(
                "http://localhost:3001/payment-success?foo=bar&resumeToken=resume_tok_1&session_id={CHECKOUT_SESSION_ID}",
                service.resolveCheckoutSuccessUrl("http://localhost:3001/payment-success?foo=bar", "resume_tok_1"));
    }

    @Test
    @DisplayName("Downgrade schedule create params should not carry metadata when from_subscription is used")
    void buildDowngradeScheduleCreateParamsDoesNotIncludeMetadata() {
        var params = BillingDomainServiceImpl.buildDowngradeScheduleCreateParams("sub_123");

        assertEquals("sub_123", params.getFromSubscription());
        assertNull(params.getMetadata());
    }

    @Test
    void buildDowngradeScheduleUpdateParamsIncludesMetadataAndPhases() {
        SubscriptionPlanEntity targetPlan = new SubscriptionPlanEntity();
        targetPlan.setPlanCode("free");
        targetPlan.setStripePriceId("price_free");

        var params = BillingDomainServiceImpl.buildDowngradeScheduleUpdateParams(
                "user_1",
                targetPlan,
                100L,
                200L,
                "price_current",
                2L);

        assertEquals(SubscriptionScheduleUpdateParams.EndBehavior.RELEASE, params.getEndBehavior());
        assertEquals(SubscriptionScheduleUpdateParams.ProrationBehavior.NONE, params.getProrationBehavior());
        assertEquals(2, params.getPhases().size());
        assertEquals(Map.of(
                "clerk_user_id", "user_1",
                "pending_plan_code", "free",
                "change_type", "downgrade"), params.getMetadata());
    }

    @Test
    void buildSubscriptionUpgradeParamsResetsAnchorAndRequiresLatestInvoiceExpansion() {
        SubscriptionPlanEntity targetPlan = new SubscriptionPlanEntity();
        targetPlan.setPlanCode("pro_yearly");
        targetPlan.setStripePriceId("price_pro_yearly");

        SubscriptionItem item = new SubscriptionItem();
        item.setId("si_123");
        item.setQuantity(2L);

        var params = BillingDomainServiceImpl.buildSubscriptionUpgradeParams(
                "user_1",
                targetPlan,
                item);

        assertEquals(SubscriptionUpdateParams.BillingCycleAnchor.NOW, params.getBillingCycleAnchor());
        assertEquals(SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE, params.getProrationBehavior());
        assertEquals(SubscriptionUpdateParams.PaymentBehavior.PENDING_IF_INCOMPLETE, params.getPaymentBehavior());
        assertEquals(List.of("latest_invoice"), params.getExpand());
        assertEquals(Map.of(
                "clerk_user_id", "user_1",
                "pending_plan_code", "pro_yearly",
                "change_type", "upgrade"), params.getMetadata());
        assertEquals(1, params.getItems().size());
        assertEquals("si_123", params.getItems().get(0).getId());
        assertEquals("price_pro_yearly", params.getItems().get(0).getPrice());
        assertEquals(2L, params.getItems().get(0).getQuantity());
    }

    @Test
    void manualUpgradeQuote_monthlyToMonthlyChargesTargetMonthlyFullPrice() {
        UpgradeChargeQuote quote = UpgradeChargeCalculator.quote(
                plan("basic_monthly", "basic", "month", 999),
                plan("plus_monthly", "plus", "month", 1999),
                LocalDateTime.parse("2026-06-23T10:00:00"),
                LocalDateTime.parse("2026-07-23T10:00:00"),
                LocalDateTime.parse("2026-06-23T10:00:00"));

        assertEquals(1999, quote.getAmountCents());
        assertEquals("monthly_full", quote.getChargeType());
        assertEquals(0, quote.getRemainingAnnualMonthsExcludingCurrent());
    }

    @Test
    void manualUpgradeQuote_annualToAnnualLastMonthChargesFullTargetAnnualPrice() {
        UpgradeChargeQuote quote = UpgradeChargeCalculator.quote(
                plan("basic_yearly", "basic", "year", 11988),
                plan("pro_yearly", "pro", "year", 23988),
                LocalDateTime.parse("2025-12-23T10:00:00"),
                LocalDateTime.parse("2026-12-23T10:00:00"),
                LocalDateTime.parse("2026-11-23T10:00:00"));

        assertEquals(23988, quote.getAmountCents());
        assertEquals("annual_diff", quote.getChargeType());
        assertEquals(0, quote.getRemainingAnnualMonthsExcludingCurrent());
    }

    @Test
    void buildManualUpgradeCheckoutIdempotencyKeyUsesOrderNumber() {
        assertEquals(
                "manual-upgrade-checkout:RO202606230001",
                BillingDomainServiceImpl.buildManualUpgradeCheckoutIdempotencyKey("RO202606230001"));
        assertFalse(
                BillingDomainServiceImpl.buildManualUpgradeCheckoutIdempotencyKey("RO202606230001")
                        .equals(BillingDomainServiceImpl.buildManualUpgradeCheckoutIdempotencyKey("RO202606230002")));
    }

    @Test
    void resolveUpgradeSuccessUrlDoesNotAppendCheckoutSessionPlaceholder() {
        BillingDomainServiceImpl service = service();

        assertEquals(
                "http://localhost:3001/payment-success?foo=bar&resumeToken=resume_tok_1",
                service.resolveUpgradeSuccessUrl(
                        "http://localhost:3001/payment-success?foo=bar",
                        "resume_tok_1"));
    }

    @Test
    void resolveSubscriptionUpgradeCheckoutUrlFallsBackToAppSuccessUrlWhenInvoiceAlreadyPaid() {
        Invoice invoice = new Invoice();
        invoice.setPaid(true);
        invoice.setStatus("paid");

        assertEquals(
                "http://localhost:3001/payment-success?resumeToken=resume_tok_1",
                BillingDomainServiceImpl.resolveSubscriptionUpgradeCheckoutUrl(
                        null,
                        invoice,
                        "http://localhost:3001/payment-success?resumeToken=resume_tok_1"));
    }

    @Test
    void clearPendingUpgradeStateForRetryExpiresPendingOrdersAndResetsSubscriptionFlag() {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(10L);
        current.setClerkUserId("user_1");
        current.setPendingPlanCode("plus_monthly");
        current.setPendingUpgradeOrderNo("RO202606230001");

        BillingDomainServiceImpl service = service();
        service.clearPendingUpgradeStateForRetry(current);

        assertNull(current.getPendingPlanCode());
        assertNull(current.getPendingEffectiveAt());
        assertNull(current.getPendingUpgradeOrderNo());
        assertNull(current.getPendingUpgradeExpiresAt());
        verify(rechargeOrderMapper).update(isNull(), any(Wrapper.class));
        verify(userSubscriptionMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void canSkipCancellationUpdateOnlyWhenNoManagedScheduleExists() {
        assertTrue(BillingDomainServiceImpl.canSkipCancellationUpdate(
                true,
                true,
                null,
                null));
        assertFalse(BillingDomainServiceImpl.canSkipCancellationUpdate(
                true,
                true,
                "sub_sched_local",
                null));
        assertFalse(BillingDomainServiceImpl.canSkipCancellationUpdate(
                false,
                false,
                null,
                "sub_sched_remote"));
    }

    @Test
    void classifyPlanChangeBlocksAnnualToMonthlyDowngrade() {
        assertEquals(
                BillingDomainServiceImpl.PlanChangeAction.UNSUPPORTED,
                BillingDomainServiceImpl.classifyPlanChange("pro", "year", "basic", "month"));
    }

    @Test
    void classifyPlanChangeSchedulesMonthlyToYearlySameTierSwitch() {
        assertEquals(
                BillingDomainServiceImpl.PlanChangeAction.DEFERRED_CHANGE,
                BillingDomainServiceImpl.classifyPlanChange("basic", "month", "basic", "year"));
    }

    @Test
    void classifyPlanChangeRejectsYearlyToMonthlySameTierSwitch() {
        assertEquals(
                BillingDomainServiceImpl.PlanChangeAction.UNSUPPORTED,
                BillingDomainServiceImpl.classifyPlanChange("basic", "year", "basic", "month"));
    }

    @Test
    void classifyPlanChangeRejectsYearlyToMonthlyEvenWhenTierWouldUpgrade() {
        assertEquals(
                BillingDomainServiceImpl.PlanChangeAction.UNSUPPORTED,
                BillingDomainServiceImpl.classifyPlanChange("basic", "year", "plus", "month"));
    }

    private BillingDomainServiceImpl service() {
        return new BillingDomainServiceImpl(
                subscriptionPlanMapper,
                addonPackageDefMapper,
                userSubscriptionMapper,
                rechargeOrderMapper);
    }

    private SubscriptionPlanEntity plan(String planCode, String tier, String billingInterval, int priceCents) {
        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode(planCode);
        plan.setTier(tier);
        plan.setBillingInterval(billingInterval);
        plan.setPriceCents(priceCents);
        return plan;
    }
}
