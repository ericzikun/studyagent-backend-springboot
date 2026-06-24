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
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.quota.PlanQuotaService;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Customer;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.SubscriptionScheduleUpdateParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
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
    @Mock
    private PlanQuotaService planQuotaService;

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
        assertEquals("annual_full", quote.getChargeType());
        assertEquals(0, quote.getRemainingAnnualMonthsExcludingCurrent());
    }

    @Test
    void manualUpgradeCheckoutEnablesInvoiceCreation() throws Exception {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(20L);
        current.setClerkUserId("user_1");
        current.setPlanCode("basic_yearly");
        current.setTier("basic");
        current.setStatus("active");
        current.setStripeCustomerId("cus_123");
        current.setStripeSubscriptionId("sub_123");
        current.setCurrentPeriodStart(LocalDateTime.parse("2026-06-24T10:00:00"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2027-06-24T10:00:00"));
        current.setQuotaPeriodStart(LocalDateTime.parse("2026-06-24T10:00:00"));

        SubscriptionPlanEntity currentPlan = plan("basic_yearly", "basic", "year", 11988);
        currentPlan.setCurrency("usd");
        currentPlan.setStripePriceId("price_basic_yearly");
        currentPlan.setIsActive(true);

        SubscriptionPlanEntity targetPlan = plan("plus_yearly", "plus", "year", 19188);
        targetPlan.setCurrency("usd");
        targetPlan.setStripePriceId("price_plus_yearly");
        targetPlan.setIsActive(true);

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(current);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan, currentPlan);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createSubscriptionCheckout(
                "user_1",
                "user@example.com",
                "plus_yearly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_tok_3");

        assertEquals("cs_test_manual_upgrade", result.getSessionId());
        assertTrue(service.lastCheckoutParams.getInvoiceCreation().getEnabled());
        assertTrue(service.lastCheckoutParams.getInvoiceCreation().getInvoiceData().getDescription()
                .contains("basic_yearly"));
        assertEquals(
                "subscription_upgrade_manual",
                service.lastCheckoutParams.getInvoiceCreation().getInvoiceData().getMetadata().get("purchase_type"));
        assertEquals(
                "plus_yearly",
                service.lastCheckoutParams.getInvoiceCreation().getInvoiceData().getMetadata().get("target_plan_code"));
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
    void shouldClearPendingScheduleStateWhenCancelingWithoutScheduleButPendingPlanExists() {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setPendingPlanCode("basic_yearly");

        assertTrue(BillingDomainServiceImpl.shouldClearPendingScheduleStateBeforeCancellation(
                true,
                null,
                current));
    }

    @Test
    void shouldNotClearPendingScheduleStateWhenResumingWithoutScheduleAndOnlyPendingPlanExists() {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setPendingPlanCode("basic_yearly");

        assertFalse(BillingDomainServiceImpl.shouldClearPendingScheduleStateBeforeCancellation(
                false,
                null,
                current));
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

    @Test
    void changeSubscriptionRejectsImmediateUpgradeAndPointsToCheckoutEndpoint() throws Exception {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setClerkUserId("user_1");
        current.setPlanCode("basic_yearly");
        current.setTier("basic");
        current.setStatus("active");
        current.setStripeSubscriptionId("sub_123");

        SubscriptionPlanEntity targetPlan = new SubscriptionPlanEntity();
        targetPlan.setPlanCode("plus_yearly");
        targetPlan.setTier("plus");
        targetPlan.setBillingInterval("year");
        targetPlan.setStripePriceId("price_plus_yearly");
        targetPlan.setIsActive(true);

        SubscriptionPlanEntity currentPlan = new SubscriptionPlanEntity();
        currentPlan.setPlanCode("basic_yearly");
        currentPlan.setTier("basic");
        currentPlan.setBillingInterval("year");
        currentPlan.setStripePriceId("price_basic_yearly");
        currentPlan.setIsActive(true);

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(current);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan, currentPlan);

        BillingDomainServiceImpl service = service();
        setStripeSecretKey(service, "sk_test_123");

        BillingDomainException exception = assertThrows(
                BillingDomainException.class,
                () -> service.changeSubscription("user_1", "plus_yearly"));

        assertEquals("UPGRADE_REQUIRES_CHECKOUT", exception.getCode());
        assertTrue(exception.getMessage().contains("/v1/payment/subscription-checkout"));
    }

    @Test
    void createSubscriptionCheckoutRetriesWithFreshCustomerWhenStoredCustomerWasDeleted() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(12L);
        subscription.setClerkUserId("user_1");
        subscription.setTier("free");
        subscription.setStatus("canceled");
        subscription.setStripeCustomerId("cus_deleted");

        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode("plus_yearly");
        plan.setTier("plus");
        plan.setBillingInterval("year");
        plan.setStripePriceId("price_plus_yearly");
        plan.setPriceCents(19999);
        plan.setCurrency("usd");
        plan.setIsActive(true);

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createSubscriptionCheckout(
                "user_1",
                "user@example.com",
                "plus_yearly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_tok_1");

        assertEquals("cs_test_retried", result.getSessionId());
        assertEquals("cus_recreated", subscription.getStripeCustomerId());
        assertEquals(2, service.checkoutAttempts);
        assertEquals(1, service.createdCustomers);
        verify(userSubscriptionMapper, times(3)).update(isNull(), any(Wrapper.class));
    }

    @Test
    void createSubscriptionCheckoutSurfacesCustomerRecreationFailureAfterMissingCustomerRetry() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(13L);
        subscription.setClerkUserId("user_2");
        subscription.setTier("free");
        subscription.setStatus("canceled");
        subscription.setStripeCustomerId("cus_deleted");

        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode("plus_yearly");
        plan.setTier("plus");
        plan.setBillingInterval("year");
        plan.setStripePriceId("price_plus_yearly");
        plan.setPriceCents(19999);
        plan.setCurrency("usd");
        plan.setIsActive(true);

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TestBillingDomainService service = new TestBillingDomainService();
        service.customerCreationFailure = new InvalidRequestException(
                "No such customer during recreation",
                "customer",
                "req_456",
                "resource_missing",
                404,
                null);
        setStripeSecretKey(service, "sk_test_123");

        BillingDomainException exception = assertThrows(
                BillingDomainException.class,
                () -> service.createSubscriptionCheckout(
                        "user_2",
                        "user@example.com",
                        "plus_yearly",
                        "http://localhost:3001/payment-success",
                        "http://localhost:3001/payment-canceled",
                        "resume_tok_2"));

        assertEquals("STRIPE_ERROR", exception.getCode());
        assertTrue(exception.getMessage().contains("Create Stripe customer failed"));
        assertEquals(1, service.checkoutAttempts);
        assertEquals(1, service.createdCustomers);
    }

    @Test
    void createSubscriptionCheckoutUsesLocalMockCheckoutWhenStripeIsNotConfigured() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(18L);
        subscription.setClerkUserId("user_4");
        subscription.setTier("free");
        subscription.setStatus("free");

        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode("plus_yearly");
        plan.setTier("plus");
        plan.setBillingInterval("year");
        plan.setPriceCents(19188);
        plan.setCurrency("usd");
        plan.setIsActive(true);

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_xxx");
        setBillingCheckoutMockEnabled(service, true);

        var result = service.createSubscriptionCheckout(
                "user_4",
                "user@example.com",
                "plus_yearly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_tok_4");

        assertTrue(result.getSessionId().startsWith("mock_cs_"));
        assertTrue(result.getCheckoutUrl().contains("session_id=" + result.getSessionId()));
        assertEquals("plus", subscription.getTier());
        assertEquals("plus_yearly", subscription.getPlanCode());
        assertEquals("active", subscription.getStatus());
        assertTrue(subscription.getStripeCustomerId().startsWith("mock_cus_"));
        assertTrue(subscription.getStripeSubscriptionId().startsWith("mock_sub_"));
        assertEquals(0, service.checkoutAttempts);
        assertEquals(0, service.createdCustomers);

        ArgumentCaptor<RechargeOrderEntity> orderCaptor = ArgumentCaptor.forClass(RechargeOrderEntity.class);
        verify(rechargeOrderMapper).insert(orderCaptor.capture());
        RechargeOrderEntity order = orderCaptor.getValue();
        assertEquals("subscription_initial", order.getOrderType());
        assertEquals("completed", order.getStatus());
        assertEquals("plus_yearly", order.getPlanCode());
        assertEquals(19188, order.getPriceCents());
        assertEquals(result.getSessionId(), order.getStripeSessionId());
        verify(planQuotaService).resetFromPaidInvoice(
                eq("user_4"),
                eq(subscription.getStripeSubscriptionId()),
                eq("plus_yearly"),
                any(Instant.class),
                any(Instant.class),
                eq(result.getSessionId()));
    }

    @Test
    void getBillingRecordsMapsLocalOrdersWithoutStripeIdentifiers() {
        RechargeOrderEntity paid = new RechargeOrderEntity();
        paid.setOrderNo("RO202606150001");
        paid.setOrderType("subscription_initial");
        paid.setStatus("completed");
        paid.setPriceCents(98214);
        paid.setCurrency("php");
        paid.setPaidAt(LocalDateTime.parse("2026-06-15T08:00:00"));
        paid.setStripeSessionId("cs_should_not_leak");

        RechargeOrderEntity failed = new RechargeOrderEntity();
        failed.setOrderNo("RO202606160001");
        failed.setOrderType("subscription_initial");
        failed.setStatus("failed");
        failed.setPriceCents(1999);
        failed.setCurrency("usd");
        failed.setCreatedAt(LocalDateTime.parse("2026-06-16T08:00:00"));

        when(rechargeOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(paid, failed));

        var records = service().getBillingRecords("user_1");

        assertEquals(2, records.size());
        assertEquals("RO202606150001", records.get(0).getId());
        assertEquals(LocalDateTime.parse("2026-06-15T08:00:00"), records.get(0).getPaidAt());
        assertEquals(98214, records.get(0).getAmountCents());
        assertEquals("php", records.get(0).getCurrency());
        assertEquals("completed", records.get(0).getStatus());
        assertEquals("subscription_initial", records.get(0).getOrderType());
        assertEquals("RO202606160001", records.get(1).getId());
        assertEquals(LocalDateTime.parse("2026-06-16T08:00:00"), records.get(1).getPaidAt());
    }

    @Test
    void createBillingPortalSessionUsesStoredCustomerAndSafeReturnUrl() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(15L);
        subscription.setClerkUserId("user_1");
        subscription.setStripeCustomerId("cus_portal_123");

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createBillingPortalSession(
                "user_1",
                "http://localhost:3001/dashboard?account=billing");

        assertEquals("https://billing.stripe.com/p/session/test_123", result.getUrl());
        assertEquals("cus_portal_123", service.lastPortalParams.getCustomer());
        assertEquals(
                "http://localhost:3001/dashboard?account=billing",
                service.lastPortalParams.getReturnUrl());
        assertEquals(1, service.portalAttempts);
    }

    @Test
    void createBillingPortalSessionUsesMockPortalUrlWhenStripeIsNotConfigured() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(16L);
        subscription.setClerkUserId("user_2");
        subscription.setStripeCustomerId("cus_portal_mock");

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_xxx");
        setBillingPortalMockUrl(service, "return-url");

        var result = service.createBillingPortalSession(
                "user_2",
                "http://localhost:3001/dashboard?account=billing");

        assertEquals(
                "http://localhost:3001/dashboard?account=billing&mockBillingPortal=stripe",
                result.getUrl());
        assertEquals(0, service.portalAttempts);
    }

    @Test
    void mockBillingPortalReturnUrlDoesNotDuplicateMarker() throws Exception {
        BillingDomainServiceImpl service = service();
        setBillingPortalMockUrl(service, "return-url");

        assertEquals(
                "http://localhost:3001/dashboard?mockBillingPortal=stripe",
                service.resolveMockBillingPortalUrl(
                        "http://localhost:3001/dashboard?mockBillingPortal=stripe",
                        "cus_portal_mock"));
    }

    @Test
    void createBillingPortalSessionRejectsMissingStripeCustomer() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(17L);
        subscription.setClerkUserId("user_3");

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");

        BillingDomainException exception = assertThrows(
                BillingDomainException.class,
                () -> service.createBillingPortalSession(
                        "user_3",
                        "http://localhost:3001/dashboard"));

        assertEquals("STRIPE_CUSTOMER_NOT_FOUND", exception.getCode());
        assertEquals(0, service.portalAttempts);
    }

    @Test
    void clearStoredStripeCustomerLeavesInMemoryCustomerWhenCompareAndClearDidNotMatch() {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(14L);
        subscription.setStripeCustomerId("cus_stale");
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        BillingDomainServiceImpl service = service();
        boolean cleared = service.clearStoredStripeCustomer(subscription);

        assertFalse(cleared);
        assertEquals("cus_stale", subscription.getStripeCustomerId());
    }

    private BillingDomainServiceImpl service() {
        return new BillingDomainServiceImpl(
                subscriptionPlanMapper,
                addonPackageDefMapper,
                userSubscriptionMapper,
                rechargeOrderMapper,
                planQuotaService);
    }

    private void setStripeSecretKey(BillingDomainServiceImpl service, String value) throws Exception {
        var field = BillingDomainServiceImpl.class.getDeclaredField("stripeSecretKey");
        field.setAccessible(true);
        field.set(service, value);
    }

    private void setBillingPortalMockUrl(BillingDomainServiceImpl service, String value) throws Exception {
        var field = BillingDomainServiceImpl.class.getDeclaredField("billingPortalMockUrl");
        field.setAccessible(true);
        field.set(service, value);
    }

    private void setBillingCheckoutMockEnabled(BillingDomainServiceImpl service, boolean value) throws Exception {
        var field = BillingDomainServiceImpl.class.getDeclaredField("billingCheckoutMockEnabled");
        field.setAccessible(true);
        field.set(service, value);
    }

    private final class TestBillingDomainService extends BillingDomainServiceImpl {
        private int checkoutAttempts;
        private int portalAttempts;
        private int createdCustomers;
        private com.stripe.exception.StripeException customerCreationFailure;
        private SessionCreateParams lastCheckoutParams;
        private com.stripe.param.billingportal.SessionCreateParams lastPortalParams;

        private TestBillingDomainService() {
            super(subscriptionPlanMapper, addonPackageDefMapper, userSubscriptionMapper, rechargeOrderMapper, planQuotaService);
        }

        @Override
        Customer createStripeCustomer(CustomerCreateParams params) throws com.stripe.exception.StripeException {
            createdCustomers++;
            if (customerCreationFailure != null) {
                throw customerCreationFailure;
            }
            Customer customer = new Customer();
            customer.setId("cus_recreated");
            return customer;
        }

        @Override
        Session createStripeCheckoutSession(SessionCreateParams params) throws com.stripe.exception.StripeException {
            checkoutAttempts++;
            if (checkoutAttempts == 1) {
                throw new InvalidRequestException(
                        "No such customer: 'cus_deleted'",
                        "customer",
                        "req_123",
                        "resource_missing",
                        404,
                        null);
            }
            Session session = new Session();
            session.setId("cs_test_retried");
            session.setUrl("https://checkout.stripe.com/c/pay/cs_test_retried");
            session.setExpiresAt(123456789L);
            session.setSubscription("sub_new");
            return session;
        }

        @Override
        Session createStripeCheckoutSession(SessionCreateParams params, RequestOptions options)
                throws com.stripe.exception.StripeException {
            lastCheckoutParams = params;
            Session session = new Session();
            session.setId("cs_test_manual_upgrade");
            session.setUrl("https://checkout.stripe.com/c/pay/cs_test_manual_upgrade");
            session.setExpiresAt(123456789L);
            return session;
        }

        @Override
        com.stripe.model.billingportal.Session createStripeBillingPortalSession(
                com.stripe.param.billingportal.SessionCreateParams params)
                throws com.stripe.exception.StripeException {
            portalAttempts++;
            lastPortalParams = params;
            com.stripe.model.billingportal.Session session = new com.stripe.model.billingportal.Session();
            session.setUrl("https://billing.stripe.com/p/session/test_123");
            return session;
        }
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
