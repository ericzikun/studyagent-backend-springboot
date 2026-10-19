package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.PricingCountryRouteEntity;
import com.studyagent.infra.entity.PricingZoneEntity;
import com.studyagent.infra.entity.PricingZonePriceEntity;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.StudyPassMapper;
import com.studyagent.infra.mapper.StudyPassProductMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.ConvertedYearlyTrialMigrationOutcome;
import com.studyagent.service.domain.billing.IntroTrialPlans;
import com.studyagent.service.domain.quota.PlanQuotaService;
import com.studyagent.service.domain.quota.QuotaVipAccessService;
import com.studyagent.service.domain.user.UserRepository;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceItem;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
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
    private StudyPassProductMapper studyPassProductMapper;
    @Mock
    private StudyPassMapper studyPassMapper;
    @Mock
    private UserSubscriptionMapper userSubscriptionMapper;
    @Mock
    private RechargeOrderMapper rechargeOrderMapper;
    @Mock
    private PlanQuotaService planQuotaService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private QuotaVipAccessService quotaVipAccessService;
    @Mock
    private UserSubscriptionBootstrapService userSubscriptionBootstrapService;

    private final StripePriceGateway regionalPrices = org.mockito.Mockito.mock(StripePriceGateway.class);
    private final BillingRegionalPricingProperties regionalConfiguration = new BillingRegionalPricingProperties();
    private final BillingPricingService regionalPricing = new BillingPricingService(regionalPrices, regionalConfiguration);

    /** 空目录的 zone 选价：全部反查为空，测试走 legacy 路径（区价测试另建 fixture）。 */
    private final ZonePricingSelectionService zonePricing = new ZonePricingSelectionService(
            new ZonePricingCatalog(
                    org.mockito.Mockito.mock(com.studyagent.infra.mapper.PricingZoneMapper.class),
                    org.mockito.Mockito.mock(com.studyagent.infra.mapper.PricingCountryRouteMapper.class),
                    org.mockito.Mockito.mock(com.studyagent.infra.mapper.PricingZonePriceMapper.class),
                    org.mockito.Mockito.mock(com.studyagent.infra.mapper.StripePriceAliasMapper.class),
                    60),
            regionalPricing);

    /** account 下周期换价预告默认无安排；区价测试按需 stub。 */
    private final SubscriptionPriceRolloutService rolloutService = org.mockito.Mockito.mock(SubscriptionPriceRolloutService.class);

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
    void getCatalogSellsSubscriptionProTrialsAndHidesHistoricalTrials() {
        SubscriptionPlanEntity proTrialMonthly = new SubscriptionPlanEntity();
        proTrialMonthly.setPlanCode(IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY);
        proTrialMonthly.setTier("pro");
        proTrialMonthly.setBillingInterval("month");
        proTrialMonthly.setOfferKind(IntroTrialPlans.OFFER_KIND_PRO_PAID_TRIAL);
        proTrialMonthly.setConvertsToPlanCode(IntroTrialPlans.PRO_CONVERSION_PLAN_CODE_MONTHLY);
        proTrialMonthly.setTrialDays(7);

        SubscriptionPlanEntity proTrialYearly = new SubscriptionPlanEntity();
        proTrialYearly.setPlanCode(IntroTrialPlans.PRO_TRIAL_PLAN_CODE_YEARLY);
        proTrialYearly.setTier("pro");
        proTrialYearly.setBillingInterval("year");
        proTrialYearly.setOfferKind(IntroTrialPlans.OFFER_KIND_PRO_PAID_TRIAL);
        proTrialYearly.setConvertsToPlanCode(IntroTrialPlans.PRO_CONVERSION_PLAN_CODE_YEARLY);
        proTrialYearly.setTrialDays(7);

        SubscriptionPlanEntity onceRetired = new SubscriptionPlanEntity();
        onceRetired.setPlanCode(IntroTrialPlans.PRO_TRIAL_ONCE_PLAN_CODE);
        onceRetired.setTier("pro");
        onceRetired.setBillingInterval("once");
        onceRetired.setOfferKind(IntroTrialPlans.OFFER_KIND_PRO_PAID_TRIAL);
        onceRetired.setConvertsToPlanCode(null);
        onceRetired.setTrialDays(7);

        SubscriptionPlanEntity monthlyTrial = new SubscriptionPlanEntity();
        monthlyTrial.setPlanCode(IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY);
        monthlyTrial.setTier("basic");
        monthlyTrial.setBillingInterval("month");
        monthlyTrial.setOfferKind(IntroTrialPlans.OFFER_KIND_BASIC_PAID_TRIAL);
        monthlyTrial.setConvertsToPlanCode(IntroTrialPlans.CONVERSION_PLAN_CODE_MONTHLY);
        monthlyTrial.setTrialDays(7);

        when(subscriptionPlanMapper.selectList(any(Wrapper.class)))
                 .thenReturn(List.of(proTrialMonthly, proTrialYearly, onceRetired, monthlyTrial));
        for (var offer : List.of(proTrialMonthly, proTrialYearly, onceRetired, monthlyTrial)) {
            offer.setPriceCents(299); offer.setCurrency("usd");
        }
        when(addonPackageDefMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        var result = service().getCatalog();

        assertEquals(1, result.getPlans().size());
        assertEquals(IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY, result.getPlans().get(0).getPlanCode());
        assertEquals(
                IntroTrialPlans.PRO_CONVERSION_PLAN_CODE_MONTHLY,
                result.getPlans().get(0).getConvertsToPlanCode());

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
    void manualUpgradeQuote_yearlySourceWithMonthlyTargetChargesFullMonthlyPrice() {
        UpgradeChargeQuote quote = UpgradeChargeCalculator.quote(
                plan("pro_trial_to_yearly", "pro", "year", 299),
                plan("plus_monthly", "plus", "month", 1999),
                LocalDateTime.parse("2026-06-24T10:00:00"),
                LocalDateTime.parse("2026-07-01T10:00:00"),
                LocalDateTime.parse("2026-06-26T10:00:00"),
                299,
                "in_trial");

        assertEquals(1999, quote.getAmountCents());
        assertEquals("monthly_full", quote.getChargeType());
        assertEquals("target_monthly_full", quote.getPricingFormula());
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
    void manualUpgradeQuote_annualCreditUsesActualNetPaidInsteadOfCatalogPrice() {
        UpgradeChargeQuote quote = UpgradeChargeCalculator.quote(
                plan("basic_yearly", "basic", "year", 12000),
                plan("plus_yearly", "plus", "year", 24000),
                LocalDateTime.parse("2026-01-01T00:00:00"),
                LocalDateTime.parse("2027-01-01T00:00:00"),
                LocalDateTime.parse("2026-06-15T00:00:00"),
                6000,
                "in_discounted");

        assertEquals(21000, quote.getAmountCents());
        assertEquals(6000, quote.getCurrentNetPaidCents());
        assertEquals("in_discounted", quote.getSourceInvoiceId());
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
    void manualUpgradeCheckoutKeepsPendingScheduleUntilPayment() throws Exception {
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
        // 放弃/未完成支付时不得丢失已排期的待切换变更：创建 Checkout 不释放 Schedule。
        assertNull(service.releasedScheduleId);
        assertEquals("sub_sched_old", service.subscriptionToRetrieve.getSchedule());
        assertEquals("free", current.getPendingPlanCode());
        assertNotNull(current.getPendingEffectiveAt());
    }

    @Test
    void proTrialManualUpgradeKeepsConversionScheduleAndPendingTarget() throws Exception {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(31L);
        current.setClerkUserId("user_1");
        current.setPlanCode("pro_trial_to_monthly");
        current.setTier("pro");
        current.setStatus("active");
        current.setStripeCustomerId("cus_123");
        current.setStripeSubscriptionId("sub_123");
        current.setStripeScheduleId("sub_sched_intro");
        current.setSubscriptionPhase("intro");
        current.setPendingPlanCode("pro_monthly");
        current.setPendingEffectiveAt(LocalDateTime.parse("2026-07-01T10:00:00"));
        current.setCurrentPeriodStart(LocalDateTime.parse("2026-06-24T10:00:00"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2026-07-01T10:00:00"));
        current.setQuotaPeriodStart(LocalDateTime.parse("2026-06-24T10:00:00"));

        SubscriptionPlanEntity currentPlan = plan("pro_trial_to_monthly", "pro", "month", 299);
        currentPlan.setCurrency("usd");
        currentPlan.setStripePriceId("price_pro_trial_monthly");
        currentPlan.setConvertsToPlanCode("pro_monthly");
        currentPlan.setIsActive(true);

        SubscriptionPlanEntity targetPlan = plan("pro_monthly", "pro", "month", 7999);
        targetPlan.setCurrency("usd");
        targetPlan.setStripePriceId("price_pro_monthly");
        targetPlan.setIsActive(true);

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(current);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan, currentPlan);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");
        service.subscriptionToRetrieve = subscription(
                "sub_123",
                "sub_sched_intro",
                "price_pro_trial_monthly",
                1782302367L,
                1782907167L);
        service.existingScheduleToRetrieve = schedule("sub_sched_intro");

        var result = service.createSubscriptionCheckout(
                "user_1",
                "user@example.com",
                "pro_monthly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_tok_6");

        assertEquals("cs_test_manual_upgrade", result.getSessionId());
        assertEquals(7999, result.getQuotedAmountCents());
        assertEquals("monthly_full", result.getUpgradeChargeType());
        // The trial Schedule is the fallback conversion if this checkout is abandoned.
        assertNull(service.releasedScheduleId);
        assertEquals("sub_sched_intro", service.subscriptionToRetrieve.getSchedule());
        assertEquals("pro_monthly", current.getPendingPlanCode());
        assertNotNull(current.getPendingEffectiveAt());
        // The 7-day intro fee is never credited and its invoice is never read.
        assertEquals(0, service.invoiceRetrieveAttempts);

        ArgumentCaptor<RechargeOrderEntity> orderCaptor = ArgumentCaptor.forClass(RechargeOrderEntity.class);
        verify(rechargeOrderMapper).insert(orderCaptor.capture());
        RechargeOrderEntity order = orderCaptor.getValue();
        assertEquals("subscription_upgrade_manual", order.getOrderType());
        assertEquals("pro_trial_to_monthly", order.getPlanCode());
        assertEquals("pro_monthly", order.getTargetPlanCode());
        assertEquals("monthly_full", order.getUpgradeChargeType());
        assertEquals(7999, order.getPriceCents());
        assertTrue(order.getBizContext().contains("\"current_net_paid_cents\":0"));
    }

    @Test
    void proTrialManualUpgradeStillResumesScheduledCancellation() throws Exception {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(32L);
        current.setClerkUserId("user_1");
        current.setPlanCode("pro_trial_to_monthly");
        current.setTier("pro");
        current.setStatus("active");
        current.setCancelAtPeriodEnd(true);
        current.setStripeCustomerId("cus_123");
        current.setStripeSubscriptionId("sub_123");
        current.setStripeScheduleId("sub_sched_intro");
        current.setPendingPlanCode("pro_monthly");
        current.setCurrentPeriodStart(LocalDateTime.parse("2026-06-24T10:00:00"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2026-07-01T10:00:00"));
        current.setQuotaPeriodStart(LocalDateTime.parse("2026-06-24T10:00:00"));

        SubscriptionPlanEntity currentPlan = plan("pro_trial_to_monthly", "pro", "month", 299);
        currentPlan.setCurrency("usd");
        currentPlan.setStripePriceId("price_pro_trial_monthly");
        currentPlan.setConvertsToPlanCode("pro_monthly");
        currentPlan.setIsActive(true);

        SubscriptionPlanEntity targetPlan = plan("pro_monthly", "pro", "month", 7999);
        targetPlan.setCurrency("usd");
        targetPlan.setStripePriceId("price_pro_monthly");
        targetPlan.setIsActive(true);

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(current);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan, currentPlan);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");
        service.subscriptionToRetrieve = subscription(
                "sub_123",
                "sub_sched_intro",
                "price_pro_trial_monthly",
                1782302367L,
                1782907167L);
        service.subscriptionToRetrieve.setCancelAtPeriodEnd(true);
        service.existingScheduleToRetrieve = schedule("sub_sched_intro");

        service.createSubscriptionCheckout(
                "user_1",
                "user@example.com",
                "pro_monthly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_tok_7");

        assertEquals(false, service.lastSubscriptionUpdateParams.getCancelAtPeriodEnd());
        assertFalse(current.getCancelAtPeriodEnd());
        assertNull(service.releasedScheduleId);
        assertEquals("pro_monthly", current.getPendingPlanCode());
    }

    @Test
    void proAnnualTrialManualUpgradeQuotesFullAnnualPriceWithoutInvoiceCredit() throws Exception {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(33L);
        current.setClerkUserId("user_1");
        current.setPlanCode("pro_trial_to_yearly");
        current.setTier("pro");
        current.setStatus("active");
        current.setStripeCustomerId("cus_123");
        current.setStripeSubscriptionId("sub_123");
        current.setStripeScheduleId("sub_sched_intro");
        current.setPendingPlanCode("pro_yearly");
        current.setCurrentPeriodStart(LocalDateTime.parse("2026-06-24T10:00:00"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2026-07-01T10:00:00"));
        current.setQuotaPeriodStart(LocalDateTime.parse("2026-06-24T10:00:00"));

        SubscriptionPlanEntity currentPlan = plan("pro_trial_to_yearly", "pro", "year", 299);
        currentPlan.setCurrency("usd");
        currentPlan.setStripePriceId("price_pro_trial_yearly");
        currentPlan.setConvertsToPlanCode("pro_yearly");
        currentPlan.setIsActive(true);

        SubscriptionPlanEntity targetPlan = plan("pro_yearly", "pro", "year", 38388);
        targetPlan.setCurrency("usd");
        targetPlan.setStripePriceId("price_pro_yearly");
        targetPlan.setIsActive(true);

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(current);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan, currentPlan);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");
        service.subscriptionToRetrieve = subscription(
                "sub_123",
                "sub_sched_intro",
                "price_pro_trial_yearly",
                1782302367L,
                1782907167L);
        service.existingScheduleToRetrieve = schedule("sub_sched_intro");

        var result = service.createSubscriptionCheckout(
                "user_1",
                "user@example.com",
                "pro_yearly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_tok_8");

        assertEquals(38388, result.getQuotedAmountCents());
        assertEquals("annual_full", result.getUpgradeChargeType());
        // A year-interval trial would otherwise read its 7-day invoice as upgrade credit.
        assertEquals(0, service.invoiceRetrieveAttempts);
        assertNull(service.releasedScheduleId);
        assertEquals("pro_yearly", current.getPendingPlanCode());
    }

    @Test
    void proTrialCanBuyLowerTierPlanImmediately() throws Exception {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(34L);
        current.setClerkUserId("user_1");
        current.setPlanCode("pro_trial_to_monthly");
        current.setTier("pro");
        current.setStatus("active");
        current.setStripeCustomerId("cus_123");
        current.setStripeSubscriptionId("sub_123");
        current.setStripeScheduleId("sub_sched_intro");
        current.setSubscriptionPhase("intro");
        current.setPendingPlanCode("pro_monthly");
        current.setCurrentPeriodStart(LocalDateTime.parse("2026-06-24T10:00:00"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2026-07-01T10:00:00"));
        current.setQuotaPeriodStart(LocalDateTime.parse("2026-06-24T10:00:00"));

        SubscriptionPlanEntity currentPlan = plan("pro_trial_to_monthly", "pro", "month", 299);
        currentPlan.setCurrency("usd");
        currentPlan.setStripePriceId("price_pro_trial_monthly");
        currentPlan.setConvertsToPlanCode("pro_monthly");
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
                "sub_sched_intro",
                "price_pro_trial_monthly",
                1782302367L,
                1782907167L);
        service.existingScheduleToRetrieve = schedule("sub_sched_intro");

        var result = service.createSubscriptionCheckout(
                "user_1",
                "user@example.com",
                "plus_monthly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_tok_9");

        assertEquals("cs_test_manual_upgrade", result.getSessionId());
        assertEquals(3999, result.getQuotedAmountCents());
        assertEquals("monthly_full", result.getUpgradeChargeType());
        assertEquals("plus_monthly", result.getTargetPlanCode());
        // Abandoning this checkout must still convert to Pro at trial end.
        assertNull(service.releasedScheduleId);
        assertEquals("pro_monthly", current.getPendingPlanCode());

        ArgumentCaptor<RechargeOrderEntity> orderCaptor = ArgumentCaptor.forClass(RechargeOrderEntity.class);
        verify(rechargeOrderMapper).insert(orderCaptor.capture());
        RechargeOrderEntity order = orderCaptor.getValue();
        assertEquals("subscription_upgrade_manual", order.getOrderType());
        assertEquals("pro_trial_to_monthly", order.getPlanCode());
        assertEquals("plus_monthly", order.getTargetPlanCode());
        assertEquals("monthly_full", order.getUpgradeChargeType());
        assertEquals(3999, order.getPriceCents());
    }

    @Test
    void convertedYearlyTrialMigrationVoidsUnpaidInvoiceAndSwitchesToMonthly() throws Exception {
        when(userSubscriptionMapper.selectOne(any(Wrapper.class)))
                .thenReturn(convertedYearlyTrialSubscription(51L, "user_1"));
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class)))
                .thenReturn(yearlyProPlan(), monthlyProPlan());
        when(rechargeOrderMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");
        service.subscriptionToRetrieve = subscription(
                "sub_123", null, "price_pro_yearly", 1789539935L, 1818677935L);
        Invoice openAnnualInvoice = new Invoice();
        openAnnualInvoice.setId("in_open_annual");
        service.openInvoicesToReturn = List.of(openAnnualInvoice);

        var outcome = service.migrateConvertedYearlyTrialToMonthly("user_1");

        assertEquals(ConvertedYearlyTrialMigrationOutcome.VOIDED_AND_SWITCHED_TO_MONTHLY, outcome);
        assertEquals(List.of("in_open_annual"), service.voidedInvoiceIds);
        assertEquals(7999, service.lastCreatedInvoiceItemAmount);
        assertEquals(1, service.createdInvoiceCount);
        assertNotNull(service.lastSubscriptionUpdateParams);
        assertNotNull(service.lastSubscriptionUpdateParams.getTrialEnd());
    }

    @Test
    void convertedYearlyTrialMigrationRespectsScheduledCancellation() throws Exception {
        when(userSubscriptionMapper.selectOne(any(Wrapper.class)))
                .thenReturn(convertedYearlyTrialSubscription(52L, "user_2"));
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class)))
                .thenReturn(yearlyProPlan(), monthlyProPlan());
        when(rechargeOrderMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");
        service.subscriptionToRetrieve = subscription(
                "sub_123", null, "price_pro_yearly", 1789539935L, 1818677935L);
        service.subscriptionToRetrieve.setCancelAtPeriodEnd(true);
        Invoice openAnnualInvoice = new Invoice();
        openAnnualInvoice.setId("in_open_annual");
        service.openInvoicesToReturn = List.of(openAnnualInvoice);

        var outcome = service.migrateConvertedYearlyTrialToMonthly("user_2");

        assertEquals(ConvertedYearlyTrialMigrationOutcome.VOIDED_ONLY_CANCEL_AT_PERIOD_END, outcome);
        assertEquals(List.of("in_open_annual"), service.voidedInvoiceIds);
        assertNull(service.lastCreatedInvoiceItemAmount);
        assertEquals(0, service.createdInvoiceCount);
        assertNull(service.lastSubscriptionUpdateParams);
    }

    @Test
    void convertedYearlyTrialMigrationSchedulesMonthlySwitchWhenAnnualTermPaid() throws Exception {
        when(userSubscriptionMapper.selectOne(any(Wrapper.class)))
                .thenReturn(convertedYearlyTrialSubscription(53L, "user_3"));
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class)))
                .thenReturn(yearlyProPlan(), monthlyProPlan());
        when(rechargeOrderMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");
        service.subscriptionToRetrieve = subscription(
                "sub_123", null, "price_pro_yearly", 1789539935L, 1818677935L);
        service.replacementScheduleToCreate = schedule("sub_sched_new");
        service.updatedScheduleToReturn = schedule("sub_sched_new");

        var outcome = service.migrateConvertedYearlyTrialToMonthly("user_3");

        assertEquals(ConvertedYearlyTrialMigrationOutcome.SCHEDULED_MONTHLY_AT_PERIOD_END, outcome);
        assertEquals(1, service.createdSchedules);
        assertEquals(1, service.updatedSchedules);
        assertNull(service.lastCreatedInvoiceItemAmount);
        assertEquals(0, service.createdInvoiceCount);
    }

    @Test
    void convertedYearlyTrialMigrationSkipsSubscriptionsWithoutYearlyTrialOrder() throws Exception {
        when(userSubscriptionMapper.selectOne(any(Wrapper.class)))
                .thenReturn(convertedYearlyTrialSubscription(54L, "user_4"));
        when(rechargeOrderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");

        var outcome = service.migrateConvertedYearlyTrialToMonthly("user_4");

        assertEquals(ConvertedYearlyTrialMigrationOutcome.SKIPPED, outcome);
        assertEquals(0, service.createdInvoiceCount);
        assertNull(service.lastSubscriptionUpdateParams);
    }

    @Test
    void convertedYearlyTrialMigrationSkipsSubscriptionsAlreadyOnMonthlyPlan() throws Exception {
        when(userSubscriptionMapper.selectOne(any(Wrapper.class)))
                .thenReturn(convertedYearlyTrialSubscription(55L, "user_5"));
        when(rechargeOrderMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(monthlyProPlan());

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");

        var outcome = service.migrateConvertedYearlyTrialToMonthly("user_5");

        assertEquals(ConvertedYearlyTrialMigrationOutcome.SKIPPED, outcome);
        assertEquals(0, service.createdInvoiceCount);
    }

    private UserSubscriptionEntity convertedYearlyTrialSubscription(long id, String clerkUserId) {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(id);
        current.setClerkUserId(clerkUserId);
        current.setPlanCode("pro_yearly");
        current.setTier("pro");
        current.setStatus("past_due");
        current.setStripeCustomerId("cus_123");
        current.setStripeSubscriptionId("sub_123");
        current.setCurrentPeriodStart(LocalDateTime.parse("2026-09-16T06:19:35"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2027-09-16T06:19:35"));
        return current;
    }

    private SubscriptionPlanEntity yearlyProPlan() {
        SubscriptionPlanEntity plan = plan("pro_yearly", "pro", "year", 38388);
        plan.setStripePriceId("price_pro_yearly");
        return plan;
    }

    private SubscriptionPlanEntity monthlyProPlan() {
        SubscriptionPlanEntity plan = plan("pro_monthly", "pro", "month", 7999);
        plan.setStripePriceId("price_pro_monthly");
        return plan;
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
    void downgradeSubscriptionPricesTargetFromContractZone() throws Exception {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(23L);
        current.setClerkUserId("user_zone_dg");
        current.setPlanCode("pro_monthly");
        current.setTier("pro");
        current.setStatus("active");
        current.setStripeSubscriptionId("sub_zone_dg");
        current.setCurrentPeriodStart(LocalDateTime.parse("2026-10-26T10:00:00"));
        current.setCurrentPeriodEnd(LocalDateTime.parse("2026-11-26T10:00:00"));
        current.setPricingZone("Z090");
        current.setPriceVersion("regional-20261018-v1");
        current.setCurrency("sgd");
        current.setPricingCountry("SG");

        SubscriptionPlanEntity currentPlan = plan("pro_monthly", "pro", "month", 3499);
        currentPlan.setCurrency("sgd");
        currentPlan.setStripePriceId("price_z090_pro");
        currentPlan.setIsActive(true);
        SubscriptionPlanEntity targetPlan = plan("plus_monthly", "plus", "month", 4599);
        targetPlan.setCurrency("sgd");
        targetPlan.setStripePriceId("price_z090_plus");
        targetPlan.setIsActive(true);

        var zoneMapper = org.mockito.Mockito.mock(com.studyagent.infra.mapper.PricingZoneMapper.class);
        var routeMapper = org.mockito.Mockito.mock(com.studyagent.infra.mapper.PricingCountryRouteMapper.class);
        var priceMapper = org.mockito.Mockito.mock(com.studyagent.infra.mapper.PricingZonePriceMapper.class);
        var aliasMapper = org.mockito.Mockito.mock(com.studyagent.infra.mapper.StripePriceAliasMapper.class);
        when(zoneMapper.selectList(any())).thenReturn(List.of(zoneRow("Z090", "SG", true)));
        when(routeMapper.selectList(any())).thenReturn(List.of());
        var proBinding = monthlyBinding("Z090", "price_z090_pro");
        proBinding.setStripeProductId(null);
        proBinding.setCurrencyAmounts("{\"sgd\":{\"amountMinor\":3499}}");
        var plusBinding = monthlyBinding("Z090", "price_z090_plus");
        plusBinding.setSkuCode("plus_monthly");
        plusBinding.setStripeProductId(null);
        plusBinding.setCurrencyAmounts("{\"sgd\":{\"amountMinor\":4599}}");
        when(priceMapper.selectList(any())).thenReturn(List.of(proBinding, plusBinding));
        when(aliasMapper.selectList(any())).thenReturn(List.of());
        var zonedPricing = new ZonePricingSelectionService(
                new ZonePricingCatalog(zoneMapper, routeMapper, priceMapper, aliasMapper, 60), regionalPricing);

        var targetPrice = new com.stripe.model.Price();
        targetPrice.setId("price_z090_plus");
        targetPrice.setCurrency("sgd");
        targetPrice.setUnitAmount(4599L);
        targetPrice.setActive(true);
        targetPrice.setLivemode(false);
        var recurring = new com.stripe.model.Price.Recurring();
        recurring.setInterval("month");
        recurring.setIntervalCount(1L);
        targetPrice.setRecurring(recurring);
        when(regionalPrices.retrieve("price_z090_plus", false)).thenReturn(targetPrice);

        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(current);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan, currentPlan);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TestBillingDomainService service = new TestBillingDomainService(zonedPricing);
        setStripeSecretKey(service, "sk_test_123");
        service.subscriptionToRetrieve = subscription("sub_zone_dg", null, "price_z090_pro", 1793000000L, 1795600000L);
        service.subscriptionToRetrieve.setCurrency("sgd");
        service.replacementScheduleToCreate = schedule("sub_sched_new");
        service.updatedScheduleToReturn = schedule("sub_sched_new");

        var result = service.downgradeSubscription("user_zone_dg", "plus_monthly");

        assertEquals("plus_monthly", result.getPendingPlanCode());
        var phases = service.lastScheduleUpdateParams.getPhases();
        assertEquals("price_z090_plus", phases.get(1).getItems().get(0).getPrice());
        assertEquals("sgd", phases.get(1).getCurrency());
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
    void clearPendingUpgradeStateForRetryKeepsDeferredPlanChange() {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(10L);
        current.setClerkUserId("user_1");
        current.setPendingPlanCode("plus_monthly");
        current.setPendingUpgradeOrderNo("RO202606230001");

        BillingDomainServiceImpl service = service();
        service.clearPendingUpgradeStateForRetry(current);

        // 只重置升级 Checkout 记账；已排期的套餐变更（deferred change）必须保留。
        assertEquals("plus_monthly", current.getPendingPlanCode());
        assertNull(current.getPendingUpgradeOrderNo());
        assertNull(current.getPendingUpgradeExpiresAt());
        verify(rechargeOrderMapper).update(isNull(), any(Wrapper.class));
        verify(userSubscriptionMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void clearPendingUpgradeStateForRetryExpiresOpenStripeCheckoutBeforeLocalState() {
        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(11L);
        current.setClerkUserId("user_1");
        current.setPendingPlanCode("plus_yearly");
        current.setPendingUpgradeOrderNo("RO_old");

        RechargeOrderEntity oldOrder = new RechargeOrderEntity();
        oldOrder.setId(301L);
        oldOrder.setClerkUserId("user_1");
        oldOrder.setOrderType("subscription_upgrade_manual");
        oldOrder.setStripeSessionId("cs_old_upgrade");
        oldOrder.setStatus("pending_checkout");
        when(rechargeOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(oldOrder));

        TestBillingDomainService service = new TestBillingDomainService();
        service.checkoutSessionStatus = "open";

        service.clearPendingUpgradeStateForRetry(current);

        assertEquals("cs_old_upgrade", service.expiredCheckoutSessionId);
        verify(rechargeOrderMapper).update(isNull(), any(Wrapper.class));
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
    void createSubscriptionCheckoutRecreatesCustomerWhenStoredCustomerObjectIsDeleted() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(17L);
        subscription.setClerkUserId("user_deleted_customer");
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

        TestBillingDomainService service = new TestBillingDomainService() {
            @Override
            Customer retrieveStripeCustomer(String id) {
                Customer customer = new Customer();
                customer.setId(id);
                customer.setDeleted(true);
                return customer;
            }

            @Override
            Session createStripeCheckoutSession(SessionCreateParams params) {
                Session session = new Session();
                session.setId("cs_test_recreated_customer");
                session.setUrl("https://checkout.stripe.com/c/pay/cs_test_recreated_customer");
                session.setExpiresAt(123456789L);
                return session;
            }
        };
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createSubscriptionCheckout(
                "user_deleted_customer",
                "user@example.com",
                "plus_yearly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_tok_deleted");

        // 删除态 Customer 对象（不是 StripeException）也要自愈：清引用并重建。
        assertEquals("cus_recreated", subscription.getStripeCustomerId());
        assertEquals(1, service.createdCustomers);
        assertEquals("cs_test_recreated_customer", result.getSessionId());
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
                studyPassProductMapper,
                studyPassMapper,
                userSubscriptionMapper,
                rechargeOrderMapper,
                planQuotaService,
                userRepository,
                quotaVipAccessService,
                userSubscriptionBootstrapService,
                    regionalPricing, zonePricing, rolloutService, org.mockito.Mockito.mock(com.studyagent.service.domain.user.ClerkClient.class)) {
            @Override Customer retrieveStripeCustomer(String id) {
                var customer = new Customer(); customer.setId(id); customer.setEmail("user@example.com");
                customer.setMetadata(java.util.Map.of()); return customer;
            }
            @Override Customer updateStripeCustomer(Customer customer, com.stripe.param.CustomerUpdateParams params) { return customer; }
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
        assertEquals(19999, result.getQuotedAmountCents());
        verify(userSubscriptionMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void createSubscriptionCheckoutRequiresPaymentResolutionForRecoverableDelinquency() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(29L);
        subscription.setClerkUserId("user_delinquent");
        subscription.setTier("basic");
        subscription.setPlanCode("basic_monthly");
        subscription.setStripeCustomerId("cus_123");
        subscription.setStripeSubscriptionId("sub_123");

        SubscriptionPlanEntity targetPlan = plan("plus_monthly", "plus", "month", 3999);
        targetPlan.setStripePriceId("price_plus_monthly");
        targetPlan.setIsActive(true);

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);
        when(userSubscriptionMapper.selectByUserForUpdate("user_delinquent")).thenReturn(subscription);

        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");

        for (String status : new String[]{"past_due", "unpaid", "incomplete"}) {
            subscription.setStatus(status);
            BillingDomainException exception = assertThrows(
                    BillingDomainException.class,
                    () -> service.createSubscriptionCheckout(
                            "user_delinquent",
                            "user@example.com",
                            "plus_monthly",
                            "http://localhost:3001/payment-success",
                            "http://localhost:3001/payment-canceled",
                            null));
            assertEquals("PAYMENT_RESOLUTION_REQUIRED", exception.getCode());
        }

        assertEquals(0, service.checkoutAttempts);
        verify(rechargeOrderMapper, never()).insert(any(RechargeOrderEntity.class));
    }

    @Test
    void createSubscriptionCheckoutAllowsFreshCheckoutAfterIncompleteExpired() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(30L);
        subscription.setClerkUserId("user_expired");
        subscription.setTier("basic");
        subscription.setPlanCode("basic_monthly");
        subscription.setStatus("incomplete_expired");
        subscription.setStripeCustomerId("cus_123");
        subscription.setStripeSubscriptionId("sub_old");

        SubscriptionPlanEntity targetPlan = plan("plus_monthly", "plus", "month", 3999);
        targetPlan.setStripePriceId("price_plus_monthly");
        targetPlan.setIsActive(true);

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);
        when(userSubscriptionMapper.selectByUserForUpdate("user_expired")).thenReturn(subscription);

        BillingDomainServiceImpl service = new BillingDomainServiceImpl(
                subscriptionPlanMapper,
                addonPackageDefMapper,
                studyPassProductMapper,
                studyPassMapper,
                userSubscriptionMapper,
                rechargeOrderMapper,
                planQuotaService,
                userRepository,
                quotaVipAccessService,
                userSubscriptionBootstrapService,
                    regionalPricing, zonePricing, rolloutService, org.mockito.Mockito.mock(com.studyagent.service.domain.user.ClerkClient.class)) {
            @Override Customer retrieveStripeCustomer(String id) {
                var customer = new Customer(); customer.setId(id); customer.setEmail("user@example.com");
                customer.setMetadata(java.util.Map.of()); return customer;
            }
            @Override Customer updateStripeCustomer(Customer customer, com.stripe.param.CustomerUpdateParams params) { return customer; }
            @Override
            Session createStripeCheckoutSession(SessionCreateParams params) {
                Session session = new Session();
                session.setId("cs_test_fresh_after_expired");
                session.setUrl("https://checkout.stripe.com/c/pay/cs_test_fresh_after_expired");
                session.setExpiresAt(123456789L);
                return session;
            }
        };
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createSubscriptionCheckout(
                "user_expired",
                "user@example.com",
                "plus_monthly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                null);

        assertEquals("cs_test_fresh_after_expired", result.getSessionId());
    }

    @Test
    void createSubscriptionCheckoutLocksPendingInitialOrderBeforeCreatingStripeSession() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(31L);
        subscription.setClerkUserId("user_lock_pending");
        subscription.setTier("free");
        subscription.setStatus("free");
        subscription.setStripeCustomerId("cus_lock_pending");

        SubscriptionPlanEntity targetPlan = plan("basic_monthly", "basic", "month", 1999);
        targetPlan.setStripePriceId("price_basic_monthly");
        targetPlan.setIsActive(true);

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);
        when(userSubscriptionMapper.selectByUserForUpdate("user_lock_pending")).thenReturn(subscription);

        BillingDomainServiceImpl service = new BillingDomainServiceImpl(
                subscriptionPlanMapper,
                addonPackageDefMapper,
                studyPassProductMapper,
                studyPassMapper,
                userSubscriptionMapper,
                rechargeOrderMapper,
                planQuotaService,
                userRepository,
                quotaVipAccessService,
                userSubscriptionBootstrapService,
                    regionalPricing, zonePricing, rolloutService, org.mockito.Mockito.mock(com.studyagent.service.domain.user.ClerkClient.class)) {
            @Override Customer retrieveStripeCustomer(String id) {
                var customer = new Customer(); customer.setId(id); customer.setEmail("user@example.com");
                customer.setMetadata(java.util.Map.of()); return customer;
            }
            @Override Customer updateStripeCustomer(Customer customer, com.stripe.param.CustomerUpdateParams params) { return customer; }
            @Override
            Session createStripeCheckoutSession(SessionCreateParams params) {
                Session session = new Session();
                session.setId("cs_test_lock_pending");
                session.setUrl("https://checkout.stripe.com/c/pay/cs_test_lock_pending");
                session.setExpiresAt(Instant.now().plusSeconds(1800).getEpochSecond());
                return session;
            }
        };
        setStripeSecretKey(service, "sk_test_123");

        service.createSubscriptionCheckout(
                "user_lock_pending",
                "user@example.com",
                "basic_monthly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                null);

        ArgumentCaptor<Wrapper> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(rechargeOrderMapper).selectOne(queryCaptor.capture());
        assertTrue(queryCaptor.getValue().getCustomSqlSegment().contains("LIMIT 1 FOR UPDATE"));
    }

    @Test
    void createSubscriptionCheckoutBootstrapsSubscriptionRowBeforeLocking() throws Exception {
        UserSubscriptionEntity concurrentSubscription = new UserSubscriptionEntity();
        concurrentSubscription.setId(32L);
        concurrentSubscription.setClerkUserId("user_concurrent_first_checkout");
        concurrentSubscription.setTier("free");
        concurrentSubscription.setStatus("free");
        concurrentSubscription.setStripeCustomerId("cus_concurrent");

        SubscriptionPlanEntity targetPlan = plan("basic_monthly", "basic", "month", 1999);
        targetPlan.setStripePriceId("price_basic_monthly");
        targetPlan.setIsActive(true);

        RechargeOrderEntity pending = new RechargeOrderEntity();
        pending.setId(203L);
        pending.setClerkUserId("user_concurrent_first_checkout");
        pending.setOrderType("subscription_initial");
        pending.setPlanCode("basic_monthly");
        pending.setStripeSessionId("cs_concurrent_open");
        pending.setCurrency("usd"); pending.setPriceCents(1999);
        pending.setStatus("pending");

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(concurrentSubscription);
        when(userSubscriptionMapper.selectByUserForUpdate("user_concurrent_first_checkout"))
                .thenReturn(concurrentSubscription);
        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(pending);

        TestBillingDomainService service = new TestBillingDomainService();
        service.checkoutSessionStatus = "open";
        service.checkoutSessionUrl = "https://checkout.stripe.com/c/pay/cs_concurrent_open";
        service.checkoutSessionExpiresAt = Instant.now().plusSeconds(1800).getEpochSecond();
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createSubscriptionCheckout(
                "user_concurrent_first_checkout",
                "user@example.com",
                "basic_monthly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                null);

        assertEquals("cs_concurrent_open", result.getSessionId());
        assertEquals(0, service.checkoutAttempts);
        verify(userSubscriptionBootstrapService).ensureExists(
                "user_concurrent_first_checkout");
        verify(userSubscriptionMapper, never()).insert(any(UserSubscriptionEntity.class));
        verify(rechargeOrderMapper, never()).insert(any(RechargeOrderEntity.class));
    }

    @Test
    void initialCheckoutExpiresStripeSessionWhenLocalOrderInsertFails() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(33L);
        subscription.setClerkUserId("user_order_failure");
        subscription.setTier("free");
        subscription.setStatus("free");
        subscription.setStripeCustomerId("cus_order_failure");

        SubscriptionPlanEntity targetPlan = plan("basic_monthly", "basic", "month", 1999);
        targetPlan.setStripePriceId("price_basic_monthly");
        targetPlan.setIsActive(true);

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(targetPlan);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);
        when(userSubscriptionMapper.selectByUserForUpdate("user_order_failure"))
                .thenReturn(subscription);
        when(rechargeOrderMapper.insert(any(RechargeOrderEntity.class)))
                .thenThrow(new IllegalStateException("simulated order insert failure"));

        TestBillingDomainService service = new TestBillingDomainService() {
            @Override
            Session createStripeCheckoutSession(SessionCreateParams params) {
                Session session = new Session();
                session.setId("cs_orphan_candidate");
                session.setUrl("https://checkout.stripe.com/c/pay/cs_orphan_candidate");
                session.setExpiresAt(Instant.now().plusSeconds(1800).getEpochSecond());
                return session;
            }
        };
        setStripeSecretKey(service, "sk_test_123");

        assertThrows(
                IllegalStateException.class,
                () -> service.createSubscriptionCheckout(
                        "user_order_failure",
                        "user@example.com",
                        "basic_monthly",
                        "http://localhost:3001/payment-success",
                        "http://localhost:3001/payment-canceled",
                        null));

        assertEquals("cs_orphan_candidate", service.expiredCheckoutSessionId);
    }

    @Test
    void createSubscriptionCheckoutReusesExistingOpenSessionForSameUserAndPlan() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(19L);
        subscription.setClerkUserId("user_5");
        subscription.setTier("free");
        subscription.setStatus("free");
        subscription.setStripeCustomerId("cus_existing");

        SubscriptionPlanEntity plan = plan("plus_yearly", "plus", "year", 19188);
        plan.setStripePriceId("price_plus_yearly");
        plan.setCurrency("usd");
        plan.setIsActive(true);

        RechargeOrderEntity pending = new RechargeOrderEntity();
        pending.setId(201L);
        pending.setClerkUserId("user_5");
        pending.setOrderType("subscription_initial");
        pending.setPlanCode("plus_yearly");
        pending.setPriceCents(19188); pending.setCurrency("usd");
        pending.setStripeSessionId("cs_existing_open");
        pending.setStatus("pending");

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);
        when(userSubscriptionMapper.selectByUserForUpdate("user_5")).thenReturn(subscription);
        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(pending);

        TestBillingDomainService service = new TestBillingDomainService();
        service.checkoutSessionStatus = "open";
        service.checkoutSessionUrl = "https://checkout.stripe.com/c/pay/cs_existing_open";
        service.checkoutSessionExpiresAt = Instant.now().plusSeconds(1800).getEpochSecond();
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createSubscriptionCheckout(
                "user_5",
                "user@example.com",
                "plus_yearly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_reuse");

        assertEquals("cs_existing_open", result.getSessionId());
        assertEquals("https://checkout.stripe.com/c/pay/cs_existing_open", result.getCheckoutUrl());
        assertEquals(0, service.checkoutAttempts);
        verify(rechargeOrderMapper, never()).insert(any(RechargeOrderEntity.class));
    }

    @Test
    void createSubscriptionCheckoutExpiresOpenSessionForDifferentPlanBeforeCreatingNewOne() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(20L);
        subscription.setClerkUserId("user_6");
        subscription.setTier("free");
        subscription.setStatus("free");
        subscription.setStripeCustomerId("cus_existing");

        SubscriptionPlanEntity plan = plan("plus_yearly", "plus", "year", 19188);
        plan.setStripePriceId("price_plus_yearly");
        plan.setCurrency("usd");
        plan.setIsActive(true);

        RechargeOrderEntity pending = new RechargeOrderEntity();
        pending.setId(202L);
        pending.setClerkUserId("user_6");
        pending.setOrderType("subscription_initial");
        pending.setPlanCode("basic_monthly");
        pending.setStripeSessionId("cs_old_plan_open");
        pending.setStatus("pending");

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);
        when(userSubscriptionMapper.selectByUserForUpdate("user_6")).thenReturn(subscription);
        when(userSubscriptionMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(pending);

        TestBillingDomainService service = new TestBillingDomainService();
        service.checkoutSessionStatus = "open";
        service.checkoutSessionUrl = "https://checkout.stripe.com/c/pay/cs_old_plan_open";
        service.checkoutSessionExpiresAt = Instant.now().plusSeconds(1800).getEpochSecond();
        setStripeSecretKey(service, "sk_test_123");

        var result = service.createSubscriptionCheckout(
                "user_6",
                "user@example.com",
                "plus_yearly",
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_replace_plan");

        assertEquals("cs_old_plan_open", service.expiredCheckoutSessionId);
        assertEquals("cs_test_retried", result.getSessionId());
        verify(rechargeOrderMapper, atLeastOnce()).update(isNull(), any(Wrapper.class));
        verify(rechargeOrderMapper).insert(any(RechargeOrderEntity.class));
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
        assertEquals(19188, result.getQuotedAmountCents());
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
                eq(result.getSessionId()),
                eq("subscription_initial"));
    }

    @Test
    void createSubscriptionCheckoutRejectsHistoricalYearlyTrialEvenIfDatabaseRowIsActive() throws Exception {
        SubscriptionPlanEntity yearlyTrial = new SubscriptionPlanEntity();
        yearlyTrial.setPlanCode(IntroTrialPlans.TRIAL_PLAN_CODE_YEARLY);
        yearlyTrial.setTier("basic");
        yearlyTrial.setBillingInterval("year");
        yearlyTrial.setOfferKind(IntroTrialPlans.OFFER_KIND_BASIC_PAID_TRIAL);
        yearlyTrial.setTrialDays(7);
        yearlyTrial.setConvertsToPlanCode(IntroTrialPlans.CONVERSION_PLAN_CODE_YEARLY);
        yearlyTrial.setPriceCents(299);
        yearlyTrial.setCurrency("usd");
        yearlyTrial.setIsActive(true);

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(yearlyTrial);

        BillingDomainServiceImpl service = service();
        setStripeSecretKey(service, "sk_test_xxx");
        setBillingCheckoutMockEnabled(service, true);

        BillingDomainException exception = assertThrows(
                BillingDomainException.class,
                () -> service.createSubscriptionCheckout(
                        "user_legacy_yearly_trial",
                        "user@example.com",
                        IntroTrialPlans.TRIAL_PLAN_CODE_YEARLY,
                        "http://localhost:3001/payment-success",
                        "http://localhost:3001/payment-canceled",
                        "resume_legacy_yearly_trial"));

        assertEquals("INVALID_PLAN", exception.getCode());
        verify(userSubscriptionMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void createSubscriptionCheckoutRejectsRetiredBasicMonthlyTrial() throws Exception {
        SubscriptionPlanEntity monthlyTrial = new SubscriptionPlanEntity();
        monthlyTrial.setPlanCode(IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY);
        monthlyTrial.setTier("basic");
        monthlyTrial.setBillingInterval("month");
        monthlyTrial.setOfferKind(IntroTrialPlans.OFFER_KIND_BASIC_PAID_TRIAL);
        monthlyTrial.setTrialDays(7);
        monthlyTrial.setConvertsToPlanCode(IntroTrialPlans.CONVERSION_PLAN_CODE_MONTHLY);
        monthlyTrial.setPriceCents(299);
        monthlyTrial.setCurrency("usd");
        monthlyTrial.setIsActive(true);

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(monthlyTrial);

        BillingDomainServiceImpl service = service();
        setStripeSecretKey(service, "sk_test_xxx");
        setBillingCheckoutMockEnabled(service, true);

        BillingDomainException exception = assertThrows(
                BillingDomainException.class,
                () -> service.createSubscriptionCheckout(
                        "user_active_basic",
                        "user@example.com",
                        IntroTrialPlans.TRIAL_PLAN_CODE_MONTHLY,
                        "http://localhost:3001/payment-success",
                        "http://localhost:3001/payment-canceled",
                        "resume_active_basic_trial"));

        assertEquals("INVALID_PLAN", exception.getCode());
        verify(rechargeOrderMapper, never()).insert(any(RechargeOrderEntity.class));
    }

    @Test
    void createSubscriptionCheckoutRejectsProTrialForActiveFormalMember() throws Exception {
        SubscriptionPlanEntity proTrial = new SubscriptionPlanEntity();
        proTrial.setPlanCode(IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY);
        proTrial.setTier("pro");
        proTrial.setBillingInterval("month");
        proTrial.setOfferKind(IntroTrialPlans.OFFER_KIND_PRO_PAID_TRIAL);
        proTrial.setTrialDays(7);
        proTrial.setConvertsToPlanCode(IntroTrialPlans.PRO_CONVERSION_PLAN_CODE_MONTHLY);
        proTrial.setPriceCents(299);
        proTrial.setCurrency("usd");
        proTrial.setIsActive(true);

        UserSubscriptionEntity activeBasic = new UserSubscriptionEntity();
        activeBasic.setId(300L);
        activeBasic.setClerkUserId("user_active_basic");
        activeBasic.setPlanCode("basic_monthly");
        activeBasic.setTier("basic");
        activeBasic.setStatus("active");
        activeBasic.setStripeSubscriptionId("mock_sub_active_basic");

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(proTrial);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(activeBasic);

        BillingDomainServiceImpl service = service();
        setStripeSecretKey(service, "sk_test_xxx");
        setBillingCheckoutMockEnabled(service, true);

        BillingDomainException exception = assertThrows(
                BillingDomainException.class,
                () -> service.createSubscriptionCheckout(
                        "user_active_basic",
                        "user@example.com",
                        IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY,
                        "http://localhost:3001/payment-success",
                        "http://localhost:3001/payment-canceled",
                        "resume_active_pro_trial"));

        assertEquals("SUBSCRIPTION_STATE_INVALID", exception.getCode());
        verify(rechargeOrderMapper, never()).insert(any(RechargeOrderEntity.class));
    }

    @Test
    void createSubscriptionCheckoutMockFulfillsProTrialWithConversionTarget() throws Exception {
        SubscriptionPlanEntity proTrial = new SubscriptionPlanEntity();
        proTrial.setPlanCode(IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY);
        proTrial.setTier("pro");
        proTrial.setBillingInterval("month");
        proTrial.setOfferKind(IntroTrialPlans.OFFER_KIND_PRO_PAID_TRIAL);
        proTrial.setTrialDays(7);
        proTrial.setConvertsToPlanCode(IntroTrialPlans.PRO_CONVERSION_PLAN_CODE_MONTHLY);
        proTrial.setPriceCents(299);
        proTrial.setCurrency("usd");
        proTrial.setAssignmentQuota(1L);
        proTrial.setDetectionQuota(3000L);
        proTrial.setHumanizerQuota(1000L);
        proTrial.setIsActive(true);

        UserSubscriptionEntity current = new UserSubscriptionEntity();
        current.setId(401L);
        current.setClerkUserId("user_pro_trial");
        current.setStatus("free");

        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(proTrial);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(current);
        when(userSubscriptionMapper.update(isNull(), any())).thenReturn(1);
        when(rechargeOrderMapper.insert(any(RechargeOrderEntity.class))).thenReturn(1);

        BillingDomainServiceImpl service = service();
        setStripeSecretKey(service, "sk_test_xxx");
        setBillingCheckoutMockEnabled(service, true);

        var result = service.createSubscriptionCheckout(
                "user_pro_trial",
                "user@example.com",
                IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY,
                "http://localhost:3001/payment-success",
                "http://localhost:3001/payment-canceled",
                "resume_pro_trial");

        assertNotNull(result.getSessionId());
        assertEquals(299, result.getQuotedAmountCents());
        verify(planQuotaService).resetFromPaidInvoice(
                eq("user_pro_trial"),
                anyString(),
                eq(IntroTrialPlans.PRO_TRIAL_PLAN_CODE_MONTHLY),
                any(Instant.class),
                any(Instant.class),
                anyString(),
                eq(IntroTrialPlans.ORDER_TYPE_INTRO_TRIAL));
        ArgumentCaptor<RechargeOrderEntity> orderCaptor = ArgumentCaptor.forClass(RechargeOrderEntity.class);
        verify(rechargeOrderMapper).insert(orderCaptor.capture());
        assertEquals(IntroTrialPlans.ORDER_TYPE_INTRO_TRIAL, orderCaptor.getValue().getOrderType());
        assertEquals(
                IntroTrialPlans.PRO_CONVERSION_PLAN_CODE_MONTHLY,
                current.getPendingPlanCode());
        assertNotNull(current.getStripeSubscriptionId());
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
        addon.setValidityMonths(2);
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
    void createAddonCheckoutRejectsNonPositiveValiditySnapshot() throws Exception {
        UserSubscriptionEntity subscription = new UserSubscriptionEntity();
        subscription.setId(19L);
        subscription.setClerkUserId("user_5");
        subscription.setStatus("active");
        subscription.setStripeCustomerId("cus_123");
        subscription.setStripeSubscriptionId("sub_123");
        AddonPackageDefEntity addon = new AddonPackageDefEntity();
        addon.setAddonCode("addon_detection_5");
        addon.setFeatureCode("ai_detection");
        addon.setStripePriceId("price_addon_detection_5");
        addon.setQuotaAmount(20000L);
        addon.setValidityMonths(0);
        addon.setPriceCents(499);
        addon.setCurrency("usd");
        addon.setIsActive(true);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);
        when(addonPackageDefMapper.selectOne(any(Wrapper.class))).thenReturn(addon);
        TestBillingDomainService service = new TestBillingDomainService();
        setStripeSecretKey(service, "sk_test_123");

        BillingDomainException exception = assertThrows(
                BillingDomainException.class,
                () -> service.createAddonCheckout(
                        "user_5",
                        "user@example.com",
                        "addon_detection_5",
                        "http://localhost:3001/payment-success",
                        "http://localhost:3001/payment-canceled",
                        "resume_tok_5"));

        assertEquals("INVALID_ADDON_SNAPSHOT", exception.getCode());
        assertEquals(0, service.checkoutAttempts);
        verify(rechargeOrderMapper, never()).insert(any(RechargeOrderEntity.class));
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

        var records = service().getBillingRecords("user_1", null, null).getItems();

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

        var records = service().getBillingRecords("user_1", null, null).getItems();

        assertEquals(1, records.size());
        assertFalse(records.get(0).isHostedInvoiceAvailable());
    }

    @Test
    void getBillingRecordsMarksLegacyRechargeWithoutStoredInvoiceAsUnavailable() {
        RechargeOrderEntity legacyRecharge = new RechargeOrderEntity();
        legacyRecharge.setOrderNo("RO202604170321172E88DF");
        legacyRecharge.setOrderType("legacy_recharge");
        legacyRecharge.setStatus("completed");
        legacyRecharge.setPriceCents(2399);
        legacyRecharge.setCurrency("usd");
        legacyRecharge.setPaidAt(LocalDateTime.parse("2026-04-17T03:21:18"));
        legacyRecharge.setStripeSessionId("cs_live_like_legacy");
        legacyRecharge.setStripeSubscriptionId("sub_live_like_legacy");

        RechargeOrderEntity legacyWithStoredInvoice = new RechargeOrderEntity();
        legacyWithStoredInvoice.setOrderNo("RO202604170321172E88E0");
        legacyWithStoredInvoice.setOrderType("legacy_recharge");
        legacyWithStoredInvoice.setStatus("completed");
        legacyWithStoredInvoice.setPriceCents(2399);
        legacyWithStoredInvoice.setCurrency("usd");
        legacyWithStoredInvoice.setPaidAt(LocalDateTime.parse("2026-04-17T03:22:18"));
        legacyWithStoredInvoice.setStripeInvoiceId("in_legacy_invoice");

        when(rechargeOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                legacyRecharge,
                legacyWithStoredInvoice));

        var records = service().getBillingRecords("user_1", null, null).getItems();

        assertEquals(2, records.size());
        assertEquals("legacy_recharge", records.get(0).getOrderType());
        assertFalse(records.get(0).isHostedInvoiceAvailable());
        assertTrue(records.get(1).isHostedInvoiceAvailable());
    }

    @Test
    void getBillingRecordsReturnsNextCursorForAdditionalPages() {
        List<RechargeOrderEntity> firstQueryRows = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            RechargeOrderEntity order = new RechargeOrderEntity();
            order.setId(100L - i);
            order.setOrderNo("RO20260615" + String.format("%04d", i));
            order.setOrderType("subscription_initial");
            order.setStatus("completed");
            order.setPriceCents(999);
            order.setCurrency("usd");
            order.setPaidAt(LocalDateTime.parse("2026-06-15T08:00:00").minusMinutes(i));
            order.setCreatedAt(LocalDateTime.parse("2026-06-15T07:00:00").minusMinutes(i));
            firstQueryRows.add(order);
        }

        RechargeOrderEntity secondPageOrder = new RechargeOrderEntity();
        secondPageOrder.setId(49L);
        secondPageOrder.setOrderNo("RO202606150051");
        secondPageOrder.setOrderType("subscription_initial");
        secondPageOrder.setStatus("completed");
        secondPageOrder.setPriceCents(999);
        secondPageOrder.setCurrency("usd");
        secondPageOrder.setPaidAt(LocalDateTime.parse("2026-06-15T07:09:00"));
        secondPageOrder.setCreatedAt(LocalDateTime.parse("2026-06-15T06:09:00"));

        when(rechargeOrderMapper.selectList(any(Wrapper.class)))
                .thenReturn(firstQueryRows)
                .thenReturn(List.of(secondPageOrder));

        var firstPage = service().getBillingRecords("user_1", null, 50);

        assertEquals(50, firstPage.getItems().size());
        assertNotNull(firstPage.getNextCursor());

        var secondPage = service().getBillingRecords("user_1", firstPage.getNextCursor(), 50);

        assertEquals(1, secondPage.getItems().size());
        assertEquals("RO202606150051", secondPage.getItems().get(0).getId());
        assertNull(secondPage.getNextCursor());

        ArgumentCaptor<Wrapper> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(rechargeOrderMapper, times(2)).selectList(queryCaptor.capture());
        String firstPageSql = queryCaptor.getAllValues().get(0).getCustomSqlSegment();
        String secondPageSql = queryCaptor.getAllValues().get(1).getCustomSqlSegment();
        assertTrue(firstPageSql.contains("ORDER BY COALESCE(paid_at, created_at, updated_at) DESC, id DESC"));
        assertTrue(secondPageSql.contains("COALESCE(paid_at, created_at, updated_at) <"));
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
        assertEquals(19188, result.getQuotedAmountCents());
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

    @Test
    void brazilQuoteBindsTrialAndRenewalBeforeAnyStripeWrite() throws Exception {
        regionalConfiguration.setEnabled(true);
        var trial = plan("pro_trial_to_monthly", "pro", "month", 299);
        trial.setStripePriceId("price_trial"); trial.setCurrency("usd"); trial.setIsActive(true);
        trial.setOfferKind("pro_paid_trial"); trial.setTrialDays(7); trial.setConvertsToPlanCode("pro_monthly");
        var monthly = plan("pro_monthly", "pro", "month", 7999);
        monthly.setStripePriceId("price_monthly"); monthly.setCurrency("usd"); monthly.setIsActive(true);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(trial, monthly);
        when(regionalPrices.retrieve("price_trial", false)).thenReturn(regionalPrice("price_trial", "week", 299, 499));
        when(regionalPrices.retrieve("price_monthly", false)).thenReturn(regionalPrice("price_monthly", "month", 7999, 39999));
        try (var scope = com.studyagent.service.domain.billing.BillingPricingContext.open("BR")) {
            var quote = service().getPurchaseQuote("user_br", "subscription", "pro_trial_to_monthly");
            assertEquals("brl", quote.getCurrency()); assertEquals(499, quote.getAmountCents());
            assertEquals(39999, quote.getRenewalAmountCents()); assertEquals("brl", quote.getRenewalCurrency());
            assertEquals(7, quote.getTrialDays());
            verify(rechargeOrderMapper, never()).insert(any(RechargeOrderEntity.class));
            verify(userSubscriptionMapper, never()).update(any(), any());
            assertEquals(299, trial.getPriceCents()); assertEquals("usd", trial.getCurrency());
        }
    }

    @Test
    void brazilConversionPhasesKeepSevenDayBoundaryAndCurrencyWhenFlagIsOff() throws Exception {
        var service = new TestBillingDomainService();
        var subscription = new Subscription(); subscription.setId("sub_br"); subscription.setCurrency("brl");
        subscription.setCurrentPeriodStart(1700000000L); subscription.setCurrentPeriodEnd(1700604800L);
        var item = new com.stripe.model.SubscriptionItem(); item.setQuantity(1L);
        var price = new com.stripe.model.Price(); price.setId("price_trial"); item.setPrice(price);
        var items = new com.stripe.model.SubscriptionItemCollection(); items.setData(List.of(item)); subscription.setItems(items);
        var conversion = plan("pro_monthly", "pro", "month", 39999); conversion.setCurrency("brl"); conversion.setStripePriceId("price_monthly");
        var schedule = new SubscriptionSchedule(); schedule.setId("sub_sched_br"); service.updatedScheduleToReturn = schedule;
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "applyIntroTrialConversionPhases", schedule, subscription, conversion, "user_br", "pro_monthly");
        var phases = service.lastScheduleUpdateParams.getPhases();
        assertEquals(2, phases.size()); assertEquals("brl", phases.get(0).getCurrency()); assertEquals("brl", phases.get(1).getCurrency());
        assertEquals(604800L, (Long) phases.get(0).getEndDate() - (Long) phases.get(0).getStartDate());
        assertEquals(phases.get(0).getEndDate(), phases.get(1).getStartDate());
        assertEquals("price_monthly", phases.get(1).getItems().get(0).getPrice());
    }

    @Test
    void enabledRegionalPricingRejectsUnrestrictedPortalBeforeCreatingSession() throws Exception {
        regionalConfiguration.setEnabled(true);
        var user = new UserSubscriptionEntity(); user.setStripeCustomerId("cus_owned");
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(user);
        var service = new TestBillingDomainService(); setStripeSecretKey(service, "sk_test_123");
        assertEquals("PRICING_CURRENCY_UNAVAILABLE", assertThrows(BillingDomainException.class,
            () -> service.createBillingPortalSession("user_1", "http://localhost:3001/pricing")).getCode());
        assertEquals(0, service.portalAttempts);
    }

    @Test
    void brlCheckoutUpdatesOwnedCustomerEmailAndPersistsConfirmedCurrency() throws Exception {
        regionalConfiguration.setEnabled(true);
        var plan = plan("pro_monthly", "pro", "month", 7999); plan.setCurrency("usd"); plan.setStripePriceId("price_monthly"); plan.setIsActive(true);
        var user = new UserSubscriptionEntity(); user.setId(71L); user.setClerkUserId("user_br"); user.setTier("free"); user.setStatus("free"); user.setStripeCustomerId("cus_owned");
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(user);
        when(userSubscriptionMapper.selectByUserForUpdate("user_br")).thenReturn(user);
        when(regionalPrices.retrieve("price_monthly", false)).thenReturn(regionalPrice("price_monthly", "month", 7999, 39999));
        when(regionalPrices.retrieve("price_monthly", true)).thenReturn(regionalPrice("price_monthly", "month", 7999, 39999));
        var service = new TestBillingDomainService(); service.checkoutAttempts = 1; setStripeSecretKey(service, "sk_test_123");
        var pending = new RechargeOrderEntity(); pending.setId(72L); pending.setClerkUserId("user_br"); pending.setPlanCode("pro_monthly");
        pending.setCurrency("usd"); pending.setPriceCents(7999); pending.setStripeSessionId("cs_old_usd"); pending.setStatus("pending");
        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(pending);
        var old = new Session(); old.setId("cs_old_usd"); old.setStatus("open"); old.setUrl("https://checkout.stripe.test/old-usd");
        old.setExpiresAt(Instant.now().plusSeconds(1800).getEpochSecond()); old.setClientReferenceId("user_br"); old.setCurrency("usd"); old.setAmountSubtotal(7999L);
        var oldLine = new com.stripe.model.LineItem(); var oldPrice = new com.stripe.model.Price(); oldPrice.setId("price_monthly"); oldLine.setPrice(oldPrice); oldLine.setQuantity(1L);
        var oldLines = new com.stripe.model.LineItemCollection(); oldLines.setData(List.of(oldLine)); old.setLineItems(oldLines); service.checkoutSnapshotToRetrieve = old;
        try (var scope = com.studyagent.service.domain.billing.BillingPricingContext.open("BR")) {
            var quote = service.getPurchaseQuote("user_br", "subscription", "pro_monthly");
            com.studyagent.service.domain.billing.BillingPricingContext.confirm(quote.getCurrency(), quote.getAmountCents(), quote.getQuoteVersion());
            service.createSubscriptionCheckout("user_br", "verified+location_BR@example.com", "pro_monthly", "http://localhost:3001/payment-success", "http://localhost:3001/payment-canceled", null);
            assertEquals("cs_old_usd", service.expiredCheckoutSessionId);
            assertEquals("brl", service.lastCheckoutParams.getCurrency());
            assertEquals(SessionCreateParams.Mode.SUBSCRIPTION, service.lastCheckoutParams.getMode());
            assertEquals("price_monthly", service.lastCheckoutParams.getLineItems().get(0).getPrice());
            assertEquals(1L, service.lastCheckoutParams.getLineItems().get(0).getQuantity());
            assertEquals("verified+location_BR@example.com", service.lastCustomerUpdateParams.getEmail());
            // 购买国家随 checkout 落到 session 与 subscription metadata，webhook 回填合同。
            assertEquals("BR", service.lastCheckoutParams.getMetadata().get("pricing_country"));
            assertEquals("BR", service.lastCheckoutParams.getSubscriptionData().getMetadata().get("pricing_country"));
            var saved = ArgumentCaptor.forClass(RechargeOrderEntity.class); verify(rechargeOrderMapper).insert(saved.capture());
            assertEquals("brl", saved.getValue().getCurrency()); assertEquals(39999, saved.getValue().getPriceCents());
        }
    }

    @Test
    void travelingKrwMemberAddonQuoteKeepsContractCurrency() throws Exception {
        regionalConfiguration.setEnabled(true);
        var user = new UserSubscriptionEntity(); user.setClerkUserId("user_kr"); user.setPlanCode("pro_monthly"); user.setTier("pro"); user.setStatus("active"); user.setStripeSubscriptionId("sub_kr");
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(user);
        var addon = new AddonPackageDefEntity(); addon.setAddonCode("extra"); addon.setValidityMonths(1); addon.setFeatureCode("humanizer"); addon.setQuotaAmount(1000L); addon.setStripePriceId("price_extra"); addon.setCurrency("usd"); addon.setPriceCents(999); addon.setIsActive(true);
        when(addonPackageDefMapper.selectOne(any(Wrapper.class))).thenReturn(addon);
        var price = new com.stripe.model.Price(); price.setId("price_extra"); price.setActive(true); price.setLivemode(false); price.setCurrency("usd"); price.setUnitAmount(999L);
        var won = new com.stripe.model.Price.CurrencyOption(); won.setUnitAmount(12300L); price.setCurrencyOptions(Map.of("krw", won));
        when(regionalPrices.retrieve("price_extra", false)).thenReturn(price);
        var service = new TestBillingDomainService(); service.subscriptionToRetrieve = new Subscription(); service.subscriptionToRetrieve.setCurrency("krw");
        try (var scope = com.studyagent.service.domain.billing.BillingPricingContext.open("BR")) {
            var quote = service.getPurchaseQuote("user_kr", "addon", "extra"); assertEquals("krw", quote.getCurrency()); assertEquals(12300, quote.getAmountCents());
        }
        try (var scope = com.studyagent.service.domain.billing.BillingPricingContext.open("US")) {
            var quote = service.getPurchaseQuote("user_kr", "addon", "extra"); assertEquals("krw", quote.getCurrency()); assertEquals(12300, quote.getAmountCents());
        }
        verify(rechargeOrderMapper, never()).insert(any(RechargeOrderEntity.class));
    }

    @Test
    void krwAmountsFormatAsMainUnitsWithoutHundredDivision() {
        // A02：KRW 等零小数币种最小单位即主单位，2490 必须展示为 ₩2,490 而非先除以 100。
        var service = service();
        String krw = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "formatBillingMoney", 2490, "krw");
        assertEquals("₩2,490", krw);
        // 两位小数币种维持旧契约：分→元除以 100。
        String usd = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "formatBillingMoney", 7999, "usd");
        assertEquals("$79.99", usd);
    }

    @Test
    void sameAmountTrialsInDifferentZonesCarryDistinctQuoteFingerprints() throws Exception {
        // A03：DE/IT 试用同额同币种，指纹 v2 的 zone context 必须把两区分开。
        regionalConfiguration.setEnabled(true);
        var zoneMapper = org.mockito.Mockito.mock(com.studyagent.infra.mapper.PricingZoneMapper.class);
        var routeMapper = org.mockito.Mockito.mock(com.studyagent.infra.mapper.PricingCountryRouteMapper.class);
        var priceMapper = org.mockito.Mockito.mock(com.studyagent.infra.mapper.PricingZonePriceMapper.class);
        var aliasMapper = org.mockito.Mockito.mock(com.studyagent.infra.mapper.StripePriceAliasMapper.class);
        when(zoneMapper.selectList(any())).thenReturn(List.of(
                zoneRow("Z060", "Europe DE", true), zoneRow("Z061", "Europe IT", true)));
        when(routeMapper.selectList(any())).thenReturn(List.of(
                routeRow("DE", "Z060"), routeRow("IT", "Z061")));
        when(priceMapper.selectList(any())).thenReturn(List.of(
                trialBinding("Z060", "price_de_trial"),
                monthlyBinding("Z060", "price_de_monthly"),
                trialBinding("Z061", "price_it_trial"),
                monthlyBinding("Z061", "price_it_monthly")));
        when(aliasMapper.selectList(any())).thenReturn(List.of());
        var zonePricing = new ZonePricingSelectionService(
                new ZonePricingCatalog(zoneMapper, routeMapper, priceMapper, aliasMapper, 60), regionalPricing);
        var service = new BillingDomainServiceImpl(
                subscriptionPlanMapper, addonPackageDefMapper, studyPassProductMapper, studyPassMapper,
                userSubscriptionMapper, rechargeOrderMapper, planQuotaService, userRepository,
                quotaVipAccessService, userSubscriptionBootstrapService,
                regionalPricing, zonePricing, rolloutService,
                org.mockito.Mockito.mock(com.studyagent.service.domain.user.ClerkClient.class));
        var trial = plan("pro_trial_to_monthly", "pro", "month", 299);
        trial.setStripePriceId("price_trial_usd"); trial.setCurrency("usd"); trial.setIsActive(true);
        trial.setOfferKind("pro_paid_trial"); trial.setTrialDays(7); trial.setConvertsToPlanCode("pro_monthly");
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(trial);
        when(subscriptionPlanMapper.selectList(any(Wrapper.class))).thenReturn(List.of(trial));
        when(addonPackageDefMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(regionalPrices.retrieve("price_de_trial", false)).thenReturn(eurPrice("price_de_trial", "week", 99L));
        when(regionalPrices.retrieve("price_it_trial", false)).thenReturn(eurPrice("price_it_trial", "week", 99L));
        when(regionalPrices.retrieve("price_de_monthly", false)).thenReturn(eurPrice("price_de_monthly", "month", 1499L));
        when(regionalPrices.retrieve("price_it_monthly", false)).thenReturn(eurPrice("price_it_monthly", "month", 1499L));

        String deVersion;
        String itVersion;
        try (var scope = com.studyagent.service.domain.billing.BillingPricingContext.open("DE")) {
            var deQuote = service.getPurchaseQuote("user_zone", "subscription", "pro_trial_to_monthly");
            assertEquals("eur", deQuote.getCurrency());
            assertEquals(99, deQuote.getAmountCents());
            assertEquals(1499, deQuote.getRenewalAmountCents());
            // 计费周期是 Stripe 的 week/1（7 天试用）。
            assertEquals("week", deQuote.getBillingInterval());
            // 目录契约保持 month（前端按 month 匹配 Pro Trial 卡片），两者不可混用。
            var trialPlan = service.getCatalog().getPlans().stream()
                    .filter(plan -> "pro_trial_to_monthly".equals(plan.getPlanCode()))
                    .findFirst().orElseThrow();
            assertEquals("month", trialPlan.getBillingInterval());
            assertEquals(99, trialPlan.getPriceCents());
            assertEquals("eur", trialPlan.getCurrency());
            deVersion = deQuote.getQuoteVersion();
        }
        try (var scope = com.studyagent.service.domain.billing.BillingPricingContext.open("IT")) {
            var itQuote = service.getPurchaseQuote("user_zone", "subscription", "pro_trial_to_monthly");
            assertEquals(99, itQuote.getAmountCents());
            itVersion = itQuote.getQuoteVersion();
        }
        assertNotEquals(deVersion, itVersion);
    }

    @Test
    void countriesOutsideBandsPriceFromDefaultOnePointZeroBand() throws Exception {
        // 区外/无可信国家：默认 Z100（1.00 档）$29.99，新用户永远看不到 legacy $79.99。
        regionalConfiguration.setEnabled(true);
        var zoneMapper = org.mockito.Mockito.mock(com.studyagent.infra.mapper.PricingZoneMapper.class);
        var routeMapper = org.mockito.Mockito.mock(com.studyagent.infra.mapper.PricingCountryRouteMapper.class);
        var priceMapper = org.mockito.Mockito.mock(com.studyagent.infra.mapper.PricingZonePriceMapper.class);
        var aliasMapper = org.mockito.Mockito.mock(com.studyagent.infra.mapper.StripePriceAliasMapper.class);
        when(zoneMapper.selectList(any())).thenReturn(List.of(zoneRow("Z100", "默认 1.00 档", true)));
        when(routeMapper.selectList(any())).thenReturn(List.of());
        var defaultBinding = monthlyBinding("Z100", "price_pro_2999");
        defaultBinding.setUsdDefaultAmountMinor(2999);
        defaultBinding.setCurrencyAmounts("{\"usd\":{\"amountMinor\":2999}}");
        when(priceMapper.selectList(any())).thenReturn(List.of(defaultBinding));
        when(aliasMapper.selectList(any())).thenReturn(List.of());
        var zonePricing = new ZonePricingSelectionService(
                new ZonePricingCatalog(zoneMapper, routeMapper, priceMapper, aliasMapper, 60), regionalPricing);
        var service = new BillingDomainServiceImpl(
                subscriptionPlanMapper, addonPackageDefMapper, studyPassProductMapper, studyPassMapper,
                userSubscriptionMapper, rechargeOrderMapper, planQuotaService, userRepository,
                quotaVipAccessService, userSubscriptionBootstrapService,
                regionalPricing, zonePricing, rolloutService,
                org.mockito.Mockito.mock(com.studyagent.service.domain.user.ClerkClient.class));
        var plan = plan("pro_monthly", "pro", "month", 7999);
        plan.setStripePriceId("price_pro_legacy"); plan.setCurrency("usd"); plan.setIsActive(true);
        when(subscriptionPlanMapper.selectOne(any(Wrapper.class))).thenReturn(plan);
        when(regionalPrices.retrieve("price_pro_2999", false)).thenReturn(usdPrice("price_pro_2999", "month", 2999L));

        try (var scope = com.studyagent.service.domain.billing.BillingPricingContext.open("ZZ")) {
            var quote = service.getPurchaseQuote("user_outside", "subscription", "pro_monthly");
            assertEquals("usd", quote.getCurrency());
            assertEquals(2999, quote.getAmountCents());
        }
    }

    @Test
    void cheaperMonthlyTargetChargesFullMonthlyAmountDespiteAnnualCredit() {
        // A08：年付 Plus 转（更便宜的）月付 Pro，按目标周期全额计价，不进年付折抵分支。
        var plusYearly = plan("plus_yearly", "plus", "year", 11999);
        var proMonthly = plan("pro_monthly", "pro", "month", 799);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        UpgradeChargeQuote charge = UpgradeChargeCalculator.quote(plusYearly, proMonthly,
                now.minusDays(10), now.plusMonths(11), now, 11999, "in_annual");
        assertEquals(799, charge.getAmountCents());
        assertEquals("monthly_full", charge.getChargeType());
        assertEquals("target_monthly_full", charge.getPricingFormula());
        assertEquals(0, charge.getRemainingAnnualMonthsExcludingCurrent());
    }

    private com.stripe.model.Price regionalPrice(String id, String interval, long usd, long brl) {
        var price = new com.stripe.model.Price(); price.setId(id); price.setCurrency("usd"); price.setUnitAmount(usd);
        price.setActive(true); price.setLivemode(false);
        var recurring = new com.stripe.model.Price.Recurring(); recurring.setInterval(interval); recurring.setIntervalCount(1L); price.setRecurring(recurring);
        var option = new com.stripe.model.Price.CurrencyOption(); option.setUnitAmount(brl); price.setCurrencyOptions(Map.of("brl", option)); return price;
    }

    /** A03/默认档用：独立区绑定集。 */
    private static PricingZoneEntity zoneRow(String zoneCode, String label, boolean active) {
        PricingZoneEntity zone = new PricingZoneEntity();
        zone.setZoneCode(zoneCode);
        zone.setLabel(label);
        zone.setActiveVersion("regional-20261018-v1");
        zone.setIsActive(active);
        return zone;
    }

    private static PricingCountryRouteEntity routeRow(String country, String zoneCode) {
        PricingCountryRouteEntity route = new PricingCountryRouteEntity();
        route.setCountryCode(country);
        route.setZoneCode(zoneCode);
        route.setCurrency("eur");
        route.setLabel(country);
        route.setIsEnabled(true);
        return route;
    }

    private static PricingZonePriceEntity trialBinding(String zoneCode, String trialPriceId) {
        PricingZonePriceEntity binding = new PricingZonePriceEntity();
        binding.setZoneCode(zoneCode);
        binding.setPriceVersion("regional-20261018-v1");
        binding.setSkuCode("pro_trial_to_monthly");
        binding.setSkuKind("subscription_plan");
        binding.setStripePriceId(trialPriceId);
        binding.setPriceType("recurring");
        binding.setBillingInterval("week");
        binding.setIntervalCount(1);
        binding.setUsdDefaultAmountMinor(299);
        binding.setCurrencyAmounts("{\"eur\":{\"amountMinor\":99}}");
        binding.setRenewalSkuCode("pro_monthly");
        binding.setIsActive(true);
        return binding;
    }

    private static PricingZonePriceEntity monthlyBinding(String zoneCode, String monthlyPriceId) {
        PricingZonePriceEntity binding = new PricingZonePriceEntity();
        binding.setZoneCode(zoneCode);
        binding.setPriceVersion("regional-20261018-v1");
        binding.setSkuCode("pro_monthly");
        binding.setSkuKind("subscription_plan");
        binding.setStripePriceId(monthlyPriceId);
        binding.setPriceType("recurring");
        binding.setBillingInterval("month");
        binding.setIntervalCount(1);
        binding.setUsdDefaultAmountMinor(7999);
        binding.setCurrencyAmounts("{\"eur\":{\"amountMinor\":1499}}");
        binding.setIsActive(true);
        return binding;
    }

    private static com.stripe.model.Price eurPrice(String id, String interval, long eur) {
        var price = new com.stripe.model.Price();
        price.setId(id); price.setCurrency("eur"); price.setUnitAmount(eur);
        price.setActive(true); price.setLivemode(false);
        var recurring = new com.stripe.model.Price.Recurring(); recurring.setInterval(interval); recurring.setIntervalCount(1L); price.setRecurring(recurring);
        return price;
    }

    private static com.stripe.model.Price usdPrice(String id, String interval, long amount) {
        var price = new com.stripe.model.Price();
        price.setId(id); price.setCurrency("usd"); price.setUnitAmount(amount);
        price.setActive(true); price.setLivemode(false);
        var recurring = new com.stripe.model.Price.Recurring(); recurring.setInterval(interval); recurring.setIntervalCount(1L); price.setRecurring(recurring);
        return price;
    }

    private BillingDomainServiceImpl service() {
        return new BillingDomainServiceImpl(
                subscriptionPlanMapper,
                addonPackageDefMapper,
                studyPassProductMapper,
                studyPassMapper,
                userSubscriptionMapper,
                rechargeOrderMapper,
                planQuotaService,
                userRepository,
                quotaVipAccessService,
                userSubscriptionBootstrapService,
                    regionalPricing, zonePricing, rolloutService, org.mockito.Mockito.mock(com.studyagent.service.domain.user.ClerkClient.class));
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

    private class TestBillingDomainService extends BillingDomainServiceImpl {
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
        private String checkoutSessionStatus;
        private Session checkoutSnapshotToRetrieve;
        private String checkoutSessionUrl;
        private Long checkoutSessionExpiresAt;
        private String expiredCheckoutSessionId;
        private String subscriptionLatestInvoiceId = "in_latest_paid";
        private Subscription subscriptionToRetrieve;
        private SubscriptionSchedule existingScheduleToRetrieve;
        private SubscriptionSchedule replacementScheduleToCreate;
        private SubscriptionSchedule updatedScheduleToReturn;
        private String releasedScheduleId;
        private int createdSchedules;
        private int updatedSchedules;
        private SubscriptionScheduleUpdateParams lastScheduleUpdateParams;
        private com.stripe.param.CustomerUpdateParams lastCustomerUpdateParams;
        private SubscriptionUpdateParams lastSubscriptionUpdateParams;
        private RequestOptions lastSubscriptionUpdateOptions;
        private List<Invoice> openInvoicesToReturn = List.of();
        private final List<String> voidedInvoiceIds = new java.util.ArrayList<>();
        private Integer lastCreatedInvoiceItemAmount;
        private String lastCreatedInvoiceItemDescription;
        private int createdInvoiceCount;
        private com.stripe.exception.StripeException scheduleUpdateFailure;

        private TestBillingDomainService() {
            this(zonePricing);
        }

        private TestBillingDomainService(ZonePricingSelectionService pricingSelection) {
            super(
                    subscriptionPlanMapper,
                    addonPackageDefMapper,
                    studyPassProductMapper,
                    studyPassMapper,
                    userSubscriptionMapper,
                    rechargeOrderMapper,
                    planQuotaService,
                    userRepository,
                    quotaVipAccessService,
                    userSubscriptionBootstrapService,
                    regionalPricing, pricingSelection, rolloutService, org.mockito.Mockito.mock(com.studyagent.service.domain.user.ClerkClient.class));
        }

        @Override
        Customer retrieveStripeCustomer(String id) {
            Customer customer = new Customer();
            customer.setId(id);
            customer.setEmail("user@example.com");
            customer.setMetadata(java.util.Map.of());
            return customer;
        }

        @Override
        Customer updateStripeCustomer(Customer customer, com.stripe.param.CustomerUpdateParams params) { lastCustomerUpdateParams = params; return customer; }

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
            invoice.setAmountPaid(11988L);
            invoice.setSubtotal(11988L);
            return invoice;
        }

        @Override
        Session retrieveStripeCheckoutSession(String sessionId) throws com.stripe.exception.StripeException {
            checkoutSessionRetrieveAttempts++;
            lastRetrievedCheckoutSessionId = sessionId;
            if (checkoutSnapshotToRetrieve != null) return checkoutSnapshotToRetrieve;
            Session session = new Session();
            session.setId(sessionId);
            session.setInvoice(checkoutSessionInvoiceId);
            session.setSubscription(checkoutSessionSubscriptionId);
            session.setStatus(checkoutSessionStatus);
            if ("cs_concurrent_open".equals(sessionId)) {
                session.setClientReferenceId("user_concurrent_first_checkout"); session.setCurrency("usd"); session.setAmountSubtotal(1999L);
                var price = new com.stripe.model.Price(); price.setId("price_basic_monthly");
                var item = new com.stripe.model.LineItem(); item.setPrice(price); item.setQuantity(1L);
                var lines = new com.stripe.model.LineItemCollection(); lines.setData(List.of(item)); session.setLineItems(lines);
            }
            if ("cs_existing_open".equals(sessionId)) {
                session.setClientReferenceId("user_5"); session.setCurrency("usd"); session.setAmountSubtotal(19188L);
                var price = new com.stripe.model.Price(); price.setId("price_plus_yearly");
                var item = new com.stripe.model.LineItem(); item.setPrice(price); item.setQuantity(1L);
                var lines = new com.stripe.model.LineItemCollection(); lines.setData(List.of(item)); session.setLineItems(lines);
            }
            session.setUrl(checkoutSessionUrl);
            session.setExpiresAt(checkoutSessionExpiresAt);
            return session;
        }

        @Override
        Session expireStripeCheckoutSession(Session session) {
            expiredCheckoutSessionId = session.getId();
            session.setStatus("expired");
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
        subscription.setCurrency("usd");
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
            lastScheduleUpdateParams = params;
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

        @Override
        Subscription updateStripeSubscription(
                Subscription subscription,
                SubscriptionUpdateParams params,
                RequestOptions options) {
            lastSubscriptionUpdateParams = params;
            lastSubscriptionUpdateOptions = options;
            subscription.setCancelAtPeriodEnd(false);
            return subscription;
        }

        @Override
        List<Invoice> listOpenSubscriptionInvoices(String subscriptionId) {
            return openInvoicesToReturn;
        }

        @Override
        Invoice voidStripeInvoice(String invoiceId) {
            voidedInvoiceIds.add(invoiceId);
            Invoice invoice = new Invoice();
            invoice.setId(invoiceId);
            return invoice;
        }

        @Override
        InvoiceItem createStripeInvoiceItem(
                String customerId,
                String subscriptionId,
                Integer amountCents,
                String currency,
                String description,
                String idempotencyKey) {
            lastCreatedInvoiceItemAmount = amountCents;
            lastCreatedInvoiceItemDescription = description;
            InvoiceItem item = new InvoiceItem();
            item.setId("ii_created");
            return item;
        }

        @Override
        Invoice createStripeSubscriptionInvoice(
                String customerId,
                String subscriptionId,
                String idempotencyKey) {
            createdInvoiceCount++;
            Invoice invoice = new Invoice();
            invoice.setId("in_created");
            return invoice;
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
        subscription.setCurrency("usd");
        subscription.setId(subscriptionId);
        subscription.setSchedule(scheduleId);
        subscription.setCurrentPeriodStart(periodStart);
        subscription.setCurrentPeriodEnd(periodEnd);
        subscription.setLatestInvoice("in_latest_paid");

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
