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
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.model.SubscriptionSchedule;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.SubscriptionScheduleCreateParams;
import com.stripe.param.SubscriptionScheduleReleaseParams;
import com.stripe.param.SubscriptionScheduleUpdateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
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
import static org.mockito.Mockito.never;

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
    void manualUpgradeCheckoutReleasesExistingPendingScheduleBeforeCreatingCheckout() throws Exception {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(21L);
        current.setClerkUserId("user_1");
        current.setPlanCode("basic_yearly");
        current.setTier("basic");
        current.setStatus("active");
        current.setStripeCustomerId("cus_123");
        current.setStripeSubscriptionId("sub_123");
        current.setStripeScheduleId("sub_sched_old");
        current.setPendingPlanCode("free");
        current.setPendingEffectiveAt(LocalDateTime.parse("2027-06-24T10:00:00"));
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
        service.subscriptionToRetrieve = subscription(
                "sub_123",
                "sub_sched_old",
                "price_basic_yearly",
                1782302367L,
                1813838367L);
        service.existingScheduleToRetrieve = schedule("sub_sched_old");

        var result = service.createSubscriptionCheckout(
                "user_1",
                "user@example.com",
                "plus_yearly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_tok_4");

        assertEquals("cs_test_manual_upgrade", result.getSessionId());
        assertEquals("sub_sched_old", service.releasedScheduleId);
        assertNull(service.subscriptionToRetrieve.getSchedule());
    }

    @Test
    void downgradeSubscriptionResumesCancelAtPeriodEndBeforeSchedulingPaidPlanChange() throws Exception {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(22L);
        current.setClerkUserId("user_1");
        current.setPlanCode("pro_monthly");
        current.setTier("pro");
        current.setStatus("active");
        current.setCancelAtPeriodEnd(true);
        current.setStripeSubscriptionId("sub_123");
        current.setCurrentPeriodStart(LocalDateTime.parse("2026-06-24T10:00:00"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2026-07-24T10:00:00"));

        SubscriptionPlanEntity currentPlan = plan("pro_monthly", "pro", "month", 7999);
        currentPlan.setCurrency("usd");
        currentPlan.setStripePriceId("price_pro_monthly");
        currentPlan.setIsActive(true);

        SubscriptionPlanEntity targetPlan = plan("plus_monthly", "plus", "month", 3999);
        targetPlan.setCurrency("usd");
        targetPlan.setStripePriceId("price_plus_monthly");
        targetPlan.setIsActive(true);

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(current);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan, currentPlan);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");
        service.subscriptionToRetrieve = subscription(
                "sub_123",
                null,
                "price_pro_monthly",
                1782302367L,
                1784894367L);
        service.subscriptionToRetrieve.setCancelAtPeriodEnd(true);
        service.replacementScheduleToCreate = schedule("sub_sched_new");
        service.updatedScheduleToReturn = schedule("sub_sched_new");

        var result = service.downgradeSubscription("user_1", "plus_monthly");

        assertEquals("plus_monthly", result.getPendingPlanCode());
        assertEquals(false, service.lastSubscriptionUpdateParams.getCancelAtPeriodEnd());
        assertFalse(current.getCancelAtPeriodEnd());
    }

    @Test
    void manualUpgradeCheckoutResumesCancelAtPeriodEndBeforeCreatingCheckout() throws Exception {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(22L);
        current.setClerkUserId("user_1");
        current.setPlanCode("basic_monthly");
        current.setTier("basic");
        current.setStatus("active");
        current.setCancelAtPeriodEnd(true);
        current.setStripeCustomerId("cus_123");
        current.setStripeSubscriptionId("sub_123");
        current.setCurrentPeriodStart(LocalDateTime.parse("2026-06-24T10:00:00"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2026-07-24T10:00:00"));
        current.setQuotaPeriodStart(LocalDateTime.parse("2026-06-24T10:00:00"));

        SubscriptionPlanEntity currentPlan = plan("basic_monthly", "basic", "month", 1999);
        currentPlan.setCurrency("usd");
        currentPlan.setStripePriceId("price_basic_monthly");
        currentPlan.setIsActive(true);

        SubscriptionPlanEntity targetPlan = plan("plus_monthly", "plus", "month", 3999);
        targetPlan.setCurrency("usd");
        targetPlan.setStripePriceId("price_plus_monthly");
        targetPlan.setIsActive(true);

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(current);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan, currentPlan);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");
        service.subscriptionToRetrieve = subscription(
                "sub_123",
                null,
                "price_basic_monthly",
                1782302367L,
                1784894367L);
        service.subscriptionToRetrieve.setCancelAtPeriodEnd(true);

        var result = service.createSubscriptionCheckout(
                "user_1",
                "user@example.com",
                "plus_monthly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_tok_5");

        assertEquals("cs_test_manual_upgrade", result.getSessionId());
        assertEquals(false, service.lastSubscriptionUpdateParams.getCancelAtPeriodEnd());
        assertFalse(current.getCancelAtPeriodEnd());
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
    void downgradeSubscriptionReleasesExistingScheduleBeforeCreatingReplacement() throws Exception {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(30L);
        current.setClerkUserId("user_1");
        current.setPlanCode("pro_monthly");
        current.setTier("pro");
        current.setStatus("active");
        current.setStripeSubscriptionId("sub_123");
        current.setStripeScheduleId("sub_sched_old");
        current.setPendingPlanCode("plus_yearly");
        current.setPendingEffectiveAt(LocalDateTime.parse("2026-07-24T11:59:27"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2026-07-24T11:59:27"));

        SubscriptionPlanEntity currentPlan = plan("pro_monthly", "pro", "month", 7999);
        currentPlan.setStripePriceId("price_pro_monthly");
        currentPlan.setIsActive(true);

        SubscriptionPlanEntity targetPlan = plan("basic_monthly", "basic", "month", 1999);
        targetPlan.setStripePriceId("price_basic_monthly");
        targetPlan.setIsActive(true);

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(current);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan, currentPlan);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");
        service.subscriptionToRetrieve = subscription(
                "sub_123",
                "sub_sched_old",
                "price_pro_monthly",
                1782302367L,
                1784894367L);
        service.existingScheduleToRetrieve = schedule("sub_sched_old");
        service.replacementScheduleToCreate = schedule("sub_sched_new");
        service.updatedScheduleToReturn = schedule("sub_sched_new");

        var result = service.downgradeSubscription("user_1", "basic_monthly");

        assertEquals("sub_sched_new", result.getStripeScheduleId());
        assertEquals("basic_monthly", result.getPendingPlanCode());
        assertEquals("sub_sched_old", service.releasedScheduleId);
        assertEquals("sub_sched_new", current.getStripeScheduleId());
        assertEquals(1, service.createdSchedules);
        assertEquals(1, service.updatedSchedules);
    }

    @Test
    void downgradeSubscriptionClearsLocalPendingStateWhenReplacementSchedulingFailsAfterRelease() throws Exception {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(31L);
        current.setClerkUserId("user_1");
        current.setPlanCode("pro_monthly");
        current.setTier("pro");
        current.setStatus("active");
        current.setStripeSubscriptionId("sub_123");
        current.setStripeScheduleId("sub_sched_old");
        current.setPendingPlanCode("plus_yearly");
        current.setPendingEffectiveAt(LocalDateTime.parse("2026-07-24T11:59:27"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2026-07-24T11:59:27"));

        SubscriptionPlanEntity currentPlan = plan("pro_monthly", "pro", "month", 7999);
        currentPlan.setStripePriceId("price_pro_monthly");
        currentPlan.setIsActive(true);

        SubscriptionPlanEntity targetPlan = plan("basic_monthly", "basic", "month", 1999);
        targetPlan.setStripePriceId("price_basic_monthly");
        targetPlan.setIsActive(true);

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(current);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan, currentPlan);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");
        service.subscriptionToRetrieve = subscription(
                "sub_123",
                "sub_sched_old",
                "price_pro_monthly",
                1782302367L,
                1784894367L);
        service.existingScheduleToRetrieve = schedule("sub_sched_old");
        service.replacementScheduleToCreate = schedule("sub_sched_new");
        service.scheduleUpdateFailure = new InvalidRequestException(
                "No such price: 'price_basic_monthly'",
                "price",
                "req_789",
                "resource_missing",
                404,
                null);

        BillingDomainException exception = assertThrows(
                BillingDomainException.class,
                () -> service.downgradeSubscription("user_1", "basic_monthly"));

        assertEquals("STRIPE_ERROR", exception.getCode());
        assertEquals("sub_sched_old", service.releasedScheduleId);
        assertNull(current.getStripeScheduleId());
        assertNull(current.getPendingPlanCode());
        assertNull(current.getPendingEffectiveAt());
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
        verify(userSubscriptionMapper, times(2)).update(isNull(), any(Wrapper.class));
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
    void createSubscriptionCheckoutDoesNotMarkPendingPlanBeforeCheckoutCompletes() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(15L);
        subscription.setClerkUserId("user_3");
        subscription.setTier("free");
        subscription.setStatus("free");
        subscription.setStripeCustomerId("cus_existing");

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

        BillingDomainServiceImpl service = new BillingDomainServiceImpl(
                subscriptionPlanMapper,
                addonPackageDefMapper,
                userSubscriptionMapper,
                rechargeOrderMapper,
                planQuotaService) {
            @Override
            Session createStripeCheckoutSession(SessionCreateParams params) {
                Session session = new Session();
                session.setId("cs_test_initial");
                session.setUrl("https://checkout.stripe.com/c/pay/cs_test_initial");
                session.setExpiresAt(123456789L);
                session.setSubscription("sub_new");
                return session;
            }
        };
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createSubscriptionCheckout(
                "user_3",
                "user@example.com",
                "plus_yearly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_tok_6");

        assertEquals("cs_test_initial", result.getSessionId());
        verify(userSubscriptionMapper, never()).update(isNull(), any(Wrapper.class));
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
    void createAddonCheckoutEnablesInvoiceCreationForHostedInvoice() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(19L);
        subscription.setClerkUserId("user_5");
        subscription.setTier("basic");
        subscription.setStatus("active");
        subscription.setStripeCustomerId("cus_123");
        subscription.setStripeSubscriptionId("sub_123");

        AddonPackageDefEntity addon = new AddonPackageDefEntity();
        addon.setAddonCode("addon_detection_5");
        addon.setFeatureCode("ai_detection");
        addon.setStripePriceId("price_addon_detection_5");
        addon.setQuotaAmount(20000L);
        addon.setPriceCents(499);
        addon.setCurrency("usd");
        addon.setIsActive(true);

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);
        when(addonPackageDefMapper.selectOne(any(Wrapper.class))).thenReturn(addon);

        TestBillingDomainService service = new TestBillingDomainService();
        service.checkoutAttempts = 1;
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createAddonCheckout(
                "user_5",
                "user@example.com",
                "addon_detection_5",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_tok_5");

        assertEquals("cs_test_retried", result.getSessionId());
        assertTrue(service.lastCheckoutParams.getInvoiceCreation().getEnabled());
        assertTrue(service.lastCheckoutParams.getInvoiceCreation().getInvoiceData().getDescription()
                .contains("addon_detection_5"));
        assertEquals("addon",
                service.lastCheckoutParams.getInvoiceCreation().getInvoiceData().getMetadata().get("purchase_type"));
        assertEquals("addon_detection_5",
                service.lastCheckoutParams.getInvoiceCreation().getInvoiceData().getMetadata().get("addon_code"));

        ArgumentCaptor<RechargeOrderEntity> orderCaptor = ArgumentCaptor.forClass(RechargeOrderEntity.class);
        verify(rechargeOrderMapper).insert(orderCaptor.capture());
        RechargeOrderEntity order = orderCaptor.getValue();
        assertEquals("addon", order.getOrderType());
        assertEquals("pending", order.getStatus());
        assertEquals("cs_test_retried", order.getStripeSessionId());
        assertEquals("sub_123", order.getStripeSubscriptionId());
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
        paid.setStripeInvoiceId("in_should_not_leak");

        RechargeOrderEntity failed = new RechargeOrderEntity();
        failed.setOrderNo("RO202606160001");
        failed.setOrderType("subscription_initial");
        failed.setStatus("failed");
        failed.setPriceCents(1999);
        failed.setCurrency("usd");
        failed.setCreatedAt(LocalDateTime.parse("2026-06-16T08:00:00"));
        failed.setStripeSessionId("cs_failed_should_not_open");

        when(rechargeOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(paid, failed));

        var records = service().getBillingRecords("user_1");

        assertEquals(2, records.size());
        assertEquals("RO202606150001", records.get(0).getId());
        assertEquals(LocalDateTime.parse("2026-06-15T08:00:00"), records.get(0).getPaidAt());
        assertEquals(98214, records.get(0).getAmountCents());
        assertEquals("php", records.get(0).getCurrency());
        assertEquals("completed", records.get(0).getStatus());
        assertEquals("subscription_initial", records.get(0).getOrderType());
        assertTrue(records.get(0).isHostedInvoiceAvailable());
        assertEquals("RO202606160001", records.get(1).getId());
        assertEquals(LocalDateTime.parse("2026-06-16T08:00:00"), records.get(1).getPaidAt());
        assertFalse(records.get(1).isHostedInvoiceAvailable());
    }

    @Test
    void getBillingRecordsMarksMockStripeReferencesAsInvoiceUnavailable() {
        RechargeOrderEntity mockPaid = new RechargeOrderEntity();
        mockPaid.setOrderNo("RO202606250001");
        mockPaid.setOrderType("subscription_initial");
        mockPaid.setStatus("completed");
        mockPaid.setPriceCents(9588);
        mockPaid.setCurrency("usd");
        mockPaid.setPaidAt(LocalDateTime.parse("2026-06-25T01:26:38"));
        mockPaid.setStripeSessionId("mock_cs_123");
        mockPaid.setStripeSubscriptionId("mock_sub_123");

        when(rechargeOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mockPaid));

        var records = service().getBillingRecords("user_1");

        assertEquals(1, records.size());
        assertFalse(records.get(0).isHostedInvoiceAvailable());
    }

    @Test
    void createSubscriptionCheckoutIgnoresMockSubscriptionWhenStripeIsConfigured() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(19L);
        subscription.setClerkUserId("user_5");
        subscription.setTier("basic");
        subscription.setPlanCode("basic_yearly");
        subscription.setStatus("active");
        subscription.setStripeCustomerId("cus_deleted");
        subscription.setStripeSubscriptionId("mock_sub_123");

        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setPlanCode("plus_yearly");
        plan.setTier("plus");
        plan.setBillingInterval("year");
        plan.setStripePriceId("price_plus_yearly");
        plan.setPriceCents(19188);
        plan.setCurrency("usd");
        plan.setIsActive(true);

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createSubscriptionCheckout(
                "user_5",
                "user@example.com",
                "plus_yearly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_tok_5");

        assertEquals("cs_test_retried", result.getSessionId());
        assertEquals("session", result.getCheckoutKind());
        assertNull(result.getQuotedAmountCents());
        assertEquals(2, service.checkoutAttempts);
    }

    @Test
    void createBillingHostedInvoiceRetrievesStripeHostedInvoiceForOwnedRecord() throws Exception {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(21L);
        order.setOrderNo("RO202606150001");
        order.setStripeInvoiceId("in_123");

        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createBillingHostedInvoice("user_1", "RO202606150001");

        assertEquals("https://invoice.stripe.com/i/test_123", result.getUrl());
        assertEquals("in_123", service.lastRetrievedInvoiceId);
        assertEquals(1, service.invoiceRetrieveAttempts);
    }

    @Test
    void createBillingHostedInvoiceRejectsRecordWithoutInvoice() throws Exception {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(22L);
        order.setOrderNo("RO202606160001");

        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");

        BillingDomainException exception = assertThrows(
                BillingDomainException.class,
                () -> service.createBillingHostedInvoice("user_1", "RO202606160001"));

        assertEquals("BILLING_INVOICE_NOT_AVAILABLE", exception.getCode());
        assertEquals(0, service.invoiceRetrieveAttempts);
    }

    @Test
    void createBillingHostedInvoiceFallsBackToCheckoutSessionInvoice() throws Exception {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(23L);
        order.setOrderNo("RO202606170001");
        order.setStripeSessionId("cs_123");

        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        TestBillingDomainService service = new TestBillingDomainService();
        service.checkoutSessionInvoiceId = "in_from_session";
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createBillingHostedInvoice("user_1", "RO202606170001");

        assertEquals("https://invoice.stripe.com/i/test_123", result.getUrl());
        assertEquals("cs_123", service.lastRetrievedCheckoutSessionId);
        assertEquals("in_from_session", service.lastRetrievedInvoiceId);
        assertEquals(1, service.checkoutSessionRetrieveAttempts);
        assertEquals(0, service.subscriptionRetrieveAttempts);
    }

    @Test
    void createBillingHostedInvoiceFallsBackToSubscriptionLatestInvoice() throws Exception {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(24L);
        order.setOrderNo("RO202606180001");
        order.setStripeSubscriptionId("sub_123");

        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        TestBillingDomainService service = new TestBillingDomainService();
        service.subscriptionLatestInvoiceId = "in_from_subscription";
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createBillingHostedInvoice("user_1", "RO202606180001");

        assertEquals("https://invoice.stripe.com/i/test_123", result.getUrl());
        assertEquals("sub_123", service.lastRetrievedSubscriptionId);
        assertEquals("in_from_subscription", service.lastRetrievedInvoiceId);
        assertEquals(1, service.subscriptionRetrieveAttempts);
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
        private int invoiceRetrieveAttempts;
        private int checkoutSessionRetrieveAttempts;
        private int subscriptionRetrieveAttempts;
        private int createdCustomers;
        private com.stripe.exception.StripeException customerCreationFailure;
        private SessionCreateParams lastCheckoutParams;
        private com.stripe.param.billingportal.SessionCreateParams lastPortalParams;
        private String lastRetrievedInvoiceId;
        private String lastRetrievedCheckoutSessionId;
        private String lastRetrievedSubscriptionId;
        private String checkoutSessionInvoiceId;
        private String checkoutSessionSubscriptionId;
        private String subscriptionLatestInvoiceId;
        private Subscription subscriptionToRetrieve;
        private SubscriptionSchedule existingScheduleToRetrieve;
        private SubscriptionSchedule replacementScheduleToCreate;
        private SubscriptionSchedule updatedScheduleToReturn;
        private String releasedScheduleId;
        private int createdSchedules;
        private int updatedSchedules;
        private SubscriptionUpdateParams lastSubscriptionUpdateParams;
        private com.stripe.exception.StripeException scheduleUpdateFailure;

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
            lastCheckoutParams = params;
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

        com.stripe.model.billingportal.Session createStripeBillingPortalSession(
                com.stripe.param.billingportal.SessionCreateParams params)
                throws com.stripe.exception.StripeException {
            portalAttempts++;
            lastPortalParams = params;
            com.stripe.model.billingportal.Session session = new com.stripe.model.billingportal.Session();
            session.setUrl("https://billing.stripe.com/p/session/test_123");
            return session;
        }

        @Override
        Invoice retrieveStripeInvoice(String invoiceId) throws com.stripe.exception.StripeException {
            invoiceRetrieveAttempts++;
            lastRetrievedInvoiceId = invoiceId;
            Invoice invoice = new Invoice();
            invoice.setId(invoiceId);
            invoice.setHostedInvoiceUrl("https://invoice.stripe.com/i/test_123");
            return invoice;
        }

        @Override
        Session retrieveStripeCheckoutSession(String sessionId) throws com.stripe.exception.StripeException {
            checkoutSessionRetrieveAttempts++;
            lastRetrievedCheckoutSessionId = sessionId;
            Session session = new Session();
            session.setId(sessionId);
            session.setInvoice(checkoutSessionInvoiceId);
            session.setSubscription(checkoutSessionSubscriptionId);
            return session;
        }

        @Override
        Subscription retrieveStripeSubscription(String subscriptionId) throws com.stripe.exception.StripeException {
            subscriptionRetrieveAttempts++;
            lastRetrievedSubscriptionId = subscriptionId;
            if (subscriptionToRetrieve != null) {
                return subscriptionToRetrieve;
            }
            Subscription subscription = new Subscription();
            subscription.setId(subscriptionId);
            subscription.setSchedule(null);
            subscription.setLatestInvoice(subscriptionLatestInvoiceId);
            return subscription;
        }

        @Override
        Subscription updateStripeSubscription(Subscription subscription, SubscriptionUpdateParams params) {
            lastSubscriptionUpdateParams = params;
            subscription.setCancelAtPeriodEnd(false);
            if (subscriptionToRetrieve != null) {
                subscriptionToRetrieve.setCancelAtPeriodEnd(false);
            }
            return subscription;
        }

        @Override
        SubscriptionSchedule retrieveStripeSubscriptionSchedule(String scheduleId) {
            return existingScheduleToRetrieve;
        }

        @Override
        SubscriptionSchedule createStripeSubscriptionSchedule(
                SubscriptionScheduleCreateParams params,
                RequestOptions options) {
            createdSchedules++;
            return replacementScheduleToCreate;
        }

        @Override
        SubscriptionSchedule updateStripeSubscriptionSchedule(
                SubscriptionSchedule schedule,
                SubscriptionScheduleUpdateParams params,
                RequestOptions options) throws com.stripe.exception.StripeException {
            if (scheduleUpdateFailure != null) {
                throw scheduleUpdateFailure;
            }
            updatedSchedules++;
            return updatedScheduleToReturn;
        }

        @Override
        SubscriptionSchedule releaseStripeSubscriptionSchedule(
                SubscriptionSchedule schedule,
                SubscriptionScheduleReleaseParams params) {
            releasedScheduleId = schedule.getId();
            if (subscriptionToRetrieve != null) {
                subscriptionToRetrieve.setSchedule(null);
            }
            return schedule;
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

    private Subscription subscription(
            String subscriptionId,
            String scheduleId,
            String priceId,
            Long periodStart,
            Long periodEnd) {
        Subscription subscription = new Subscription();
        subscription.setId(subscriptionId);
        subscription.setSchedule(scheduleId);
        subscription.setCurrentPeriodStart(periodStart);
        subscription.setCurrentPeriodEnd(periodEnd);

        Price price = new Price();
        price.setId(priceId);
        SubscriptionItem item = new SubscriptionItem();
        item.setId("si_123");
        item.setPrice(price);
        item.setQuantity(1L);

        SubscriptionItemCollection items = new SubscriptionItemCollection();
        items.setData(List.of(item));
        subscription.setItems(items);
        return subscription;
    }

    private SubscriptionSchedule schedule(String scheduleId) {
        SubscriptionSchedule schedule = new SubscriptionSchedule();
        schedule.setId(scheduleId);
        schedule.setStatus("active");
        return schedule;
    }
}
