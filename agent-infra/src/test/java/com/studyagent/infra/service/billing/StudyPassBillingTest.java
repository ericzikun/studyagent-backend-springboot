package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.StudyPassEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import java.time.Instant;
import com.studyagent.infra.entity.StudyPassProductEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.StudyPassMapper;
import com.studyagent.infra.mapper.StudyPassProductMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.infra.testutil.MybatisPlusTableInfoTestHelper;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.BillingStudyPass;
import com.studyagent.service.domain.billing.SubscriptionResult;
import com.studyagent.service.domain.quota.PlanQuotaService;
import com.studyagent.service.domain.quota.QuotaVipAccessService;
import com.studyagent.service.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.any;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 题库通行证的契约边界。
 *
 * <p>重点覆盖两件容易写错的事：通行证不得被当成会员权益（不放行任何工具额度），
 * 以及重复投递/重复购买不得延长或复制有效期。
 */
@ExtendWith(MockitoExtension.class)
class StudyPassBillingTest {
    private static final String PASS_CODE = "study_pass_30d";
    private static final String USER = "user_study_pass";

    @BeforeAll
    static void initTableInfo() {
        MybatisPlusTableInfoTestHelper.initTableInfo(RechargeOrderEntity.class);
        MybatisPlusTableInfoTestHelper.initTableInfo(StudyPassEntity.class);
        MybatisPlusTableInfoTestHelper.initTableInfo(StudyPassProductEntity.class);
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
                userSubscriptionBootstrapService);
    }

    private void setStripeSecretKey(BillingDomainServiceImpl service, String value) throws Exception {
        var field = BillingDomainServiceImpl.class.getDeclaredField("stripeSecretKey");
        field.setAccessible(true);
        field.set(service, value);
    }

    private StudyPassProductEntity sellableProduct(Integer priceCents, Integer validityDays) {
        StudyPassProductEntity product = new StudyPassProductEntity();
        product.setPassCode(PASS_CODE);
        product.setStripeProductId("prod_study_pass");
        product.setStripePriceId("price_study_pass");
        product.setPriceCents(priceCents);
        product.setCurrency("usd");
        product.setValidityDays(validityDays);
        product.setIsActive(true);
        return product;
    }

    private StudyPassEntity passExpiringIn(long days) {
        StudyPassEntity pass = new StudyPassEntity();
        pass.setClerkUserId(USER);
        pass.setPassCode(PASS_CODE);
        pass.setStatus("active");
        pass.setStartedAt(LocalDateTime.now().minusDays(30));
        pass.setExpiresAt(LocalDateTime.now().plusDays(days));
        return pass;
    }

    // ---------------- catalog ----------------

    @Test
    void getCatalogExposesActiveStudyPass() {
        when(subscriptionPlanMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(addonPackageDefMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(studyPassProductMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(sellableProduct(99, 30)));

        BillingStudyPass studyPass = service().getCatalog().getStudyPass();

        assertNotNull(studyPass);
        assertEquals(PASS_CODE, studyPass.getPassCode());
        assertEquals(99, studyPass.getPriceCents());
        assertEquals(30, studyPass.getValidityDays());
        assertEquals("price_study_pass", studyPass.getStripePriceId());
    }

    @Test
    void getCatalogOmitsStudyPassWhenNotSellable() {
        when(subscriptionPlanMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(addonPackageDefMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(studyPassProductMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        // 未启用 = 前端完全不展示售卖入口，这是 Stripe Price 就绪前的守门方式。
        assertNull(service().getCatalog().getStudyPass());
    }

    // ---------------- checkout guards ----------------

    @Test
    void createStudyPassCheckoutRejectsWhilePassIsActive() throws Exception {
        BillingDomainServiceImpl service = service();
        setStripeSecretKey(service, "sk_test_study_pass");
        when(studyPassProductMapper.selectOne(any(Wrapper.class)))
                .thenReturn(sellableProduct(99, 30));
        when(studyPassMapper.selectOne(any(Wrapper.class))).thenReturn(passExpiringIn(5));
        stubUserLock();

        BillingDomainException error = assertThrows(
                BillingDomainException.class,
                () -> service.createStudyPassCheckout(USER, "a@b.com", PASS_CODE, null, null, null));

        assertEquals("STUDY_PASS_ALREADY_ACTIVE", error.getCode());
    }

    @Test
    void createStudyPassCheckoutRejectsWhenPriceIsMissing() throws Exception {
        stubUserLock();
        BillingDomainServiceImpl service = service();
        setStripeSecretKey(service, "sk_test_study_pass");
        StudyPassProductEntity unconfigured = sellableProduct(99, 30);
        unconfigured.setStripePriceId(null);
        when(studyPassProductMapper.selectOne(any(Wrapper.class))).thenReturn(unconfigured);

        BillingDomainException error = assertThrows(
                BillingDomainException.class,
                () -> service.createStudyPassCheckout(USER, "a@b.com", PASS_CODE, null, null, null));

        assertEquals("STUDY_PASS_PRICE_NOT_CONFIGURED", error.getCode());
    }

    @Test
    void createStudyPassCheckoutRejectsWhenPassIsNotSellable() throws Exception {
        stubUserLock();
        BillingDomainServiceImpl service = service();
        setStripeSecretKey(service, "sk_test_study_pass");
        when(studyPassProductMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BillingDomainException error = assertThrows(
                BillingDomainException.class,
                () -> service.createStudyPassCheckout(USER, "a@b.com", PASS_CODE, null, null, null));

        assertEquals("STUDY_PASS_NOT_SELLABLE", error.getCode());
    }

    // ---------------- fulfillment ----------------

    @Test
    void fulfillCreatesAFreshThirtyDayWindow() {
        stubFulfillmentOrder();
        when(studyPassMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(studyPassProductMapper.selectOne(any(Wrapper.class)))
                .thenReturn(sellableProduct(99, 30));

        service().fulfillStudyPassPayment(USER, PASS_CODE, "cs_study_pass", "pi_study_pass");

        ArgumentCaptor<StudyPassEntity> captor = ArgumentCaptor.forClass(StudyPassEntity.class);
        verify(studyPassMapper).insert(captor.capture());
        StudyPassEntity saved = captor.getValue();

        assertEquals(USER, saved.getClerkUserId());
        assertEquals(PASS_CODE, saved.getPassCode());
        assertEquals("active", saved.getStatus());
        assertEquals("cs_study_pass", saved.getStripeCheckoutSessionId());
        assertEquals("pi_study_pass", saved.getStripePaymentIntentId());
        // 独立计算 30 天，不读取也不累加任何已有记录。
        assertEquals(30L, ChronoUnit.DAYS.between(saved.getStartedAt(), saved.getExpiresAt()));
        assertNull(saved.getUpgradeCreditUsedAt());
    }

    @Test
    void fulfillDoesNotAccumulateOnAnExpiredPass() {
        stubFulfillmentOrder();
        when(studyPassMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(studyPassProductMapper.selectOne(any(Wrapper.class)))
                .thenReturn(sellableProduct(99, 30));

        LocalDateTime before = LocalDateTime.now();
        service().fulfillStudyPassPayment(USER, PASS_CODE, "cs_second", "pi_second");

        ArgumentCaptor<StudyPassEntity> captor = ArgumentCaptor.forClass(StudyPassEntity.class);
        verify(studyPassMapper).insert(captor.capture());
        StudyPassEntity saved = captor.getValue();

        // 新窗口从本次购买开始，而不是从上一张通行证的到期日顺延。
        assertTrue(!saved.getStartedAt().isBefore(before));
        assertEquals(30L, ChronoUnit.DAYS.between(saved.getStartedAt(), saved.getExpiresAt()));
    }

    @Test
    void fulfillIsIdempotentForTheSameCheckoutSession() {
        stubFulfillmentOrder();
        when(studyPassMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        service().fulfillStudyPassPayment(USER, PASS_CODE, "cs_replayed", "pi_replayed");

        verify(studyPassMapper, never()).insert(any(StudyPassEntity.class));
    }

    // ---------------- upgrade credit ----------------

    @Test
    void consumeUpgradeCreditMarksItUsed() {
        StudyPassEntity pass = passExpiringIn(10);
        pass.setId(42L);
        when(studyPassMapper.selectById(42L)).thenReturn(pass);

        service().consumeStudyPassUpgradeCredit(42L);

        verify(studyPassMapper).updateById(pass);
        assertNotNull(pass.getUpgradeCreditUsedAt());
    }

    @Test
    void consumeUpgradeCreditIsIdempotentAndDoesNotRetroactivelyApply() {
        LocalDateTime originalUse = LocalDateTime.now().minusDays(3);
        StudyPassEntity pass = passExpiringIn(10);
        pass.setId(42L);
        pass.setUpgradeCreditUsedAt(originalUse);
        when(studyPassMapper.selectById(42L)).thenReturn(pass);

        service().consumeStudyPassUpgradeCredit(42L);

        verify(studyPassMapper, never()).updateById(any(StudyPassEntity.class));
        assertEquals(originalUse, pass.getUpgradeCreditUsedAt());
    }

    // ---------------- account entitlement ----------------

    @Test
    void activePassGrantsReadingWithoutPaidEntitlements() {
        stubFreeAccount();
        when(studyPassMapper.selectOne(any(Wrapper.class))).thenReturn(passExpiringIn(10));

        SubscriptionResult result = service().getCurrentSubscription(USER);

        assertTrue(result.getCanReadStudyLibrary());
        // 通行证绝不能被当成会员：不放行任何工具额度。
        assertEquals(Boolean.FALSE, result.getCanConsumePaidEntitlements());
        assertEquals("free", result.getTier());
        assertNotNull(result.getStudyPass());
        assertEquals(Boolean.TRUE, result.getStudyPass().getActive());
        assertEquals(Boolean.TRUE, result.getStudyPass().getUpgradeCreditAvailable());
        assertEquals(Boolean.FALSE, result.getStudyPass().getPurchasable());
    }

    @Test
    void expiredPassKeepsDisplayDataButGrantsNothing() {
        stubFreeAccount();
        StudyPassEntity expired = passExpiringIn(-3);
        // 第一次查询找有效通行证（无），第二次查询找最近一条（已过期）。
        when(studyPassMapper.selectOne(any(Wrapper.class))).thenReturn(null, expired);

        SubscriptionResult result = service().getCurrentSubscription(USER);

        assertEquals(Boolean.FALSE, result.getCanReadStudyLibrary());
        assertNotNull(result.getStudyPass());
        assertEquals(Boolean.FALSE, result.getStudyPass().getActive());
        assertEquals(Boolean.TRUE, result.getStudyPass().getPurchasable());
        assertEquals(Boolean.FALSE, result.getStudyPass().getUpgradeCreditAvailable());
    }

    @Test
    void readerWithoutAnyPassHasNoStudyPassPayload() {
        stubFreeAccount();
        when(studyPassMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        SubscriptionResult result = service().getCurrentSubscription(USER);

        assertEquals(Boolean.FALSE, result.getCanReadStudyLibrary());
        assertNull(result.getStudyPass());
    }

    private void stubUserLock() {
        UserSubscriptionEntity user = new UserSubscriptionEntity();
        user.setClerkUserId(USER);
        user.setStripeCustomerId("cus_study");
        when(userSubscriptionMapper.selectByUserForUpdate(USER)).thenReturn(user);
    }

    private RechargeOrderEntity stubFulfillmentOrder() {
        stubUserLock();
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setId(12L);
        order.setClerkUserId(USER);
        order.setPackageCode(PASS_CODE);
        order.setStatus("pending");
        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        return order;
    }

    @Test
    void fulfillsSnapshotEvenAfterProductIsRemovedFromSale() {
        RechargeOrderEntity order = stubFulfillmentOrder();
        order.setBizContext("{\"validityDays\":30}");
        assertTrue(service().fulfillStudyPassPayment(USER, PASS_CODE, "cs_paid", "pi_paid"));
        verify(studyPassProductMapper, never()).selectOne(any(Wrapper.class));
        ArgumentCaptor<StudyPassEntity> saved = ArgumentCaptor.forClass(StudyPassEntity.class);
        verify(studyPassMapper).insert(saved.capture());
        assertEquals(12L, saved.getValue().getOrderId());
        assertEquals(30L, ChronoUnit.DAYS.between(saved.getValue().getStartedAt(), saved.getValue().getExpiresAt()));
    }

    @Test
    void legacyFulfillmentDoesNotFilterInactiveProducts() {
        stubFulfillmentOrder();
        StudyPassProductEntity retired = sellableProduct(99, 30);
        retired.setIsActive(false);
        retired.setStripePriceId(null);
        when(studyPassProductMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            Wrapper<?> query = invocation.getArgument(0);
            assertFalse(query.getSqlSegment().contains("is_active"));
            return retired;
        });
        assertTrue(service().fulfillStudyPassPayment(USER, PASS_CODE, "cs_legacy", "pi_legacy"));
    }

    @Test
    void secondPaidSessionRequiresRefundWithoutGrantingOrExtending() {
        RechargeOrderEntity order = stubFulfillmentOrder();
        when(studyPassMapper.selectOne(any(Wrapper.class))).thenReturn(passExpiringIn(15));
        assertFalse(service().fulfillStudyPassPayment(USER, PASS_CODE, "cs_second", "pi_second"));
        assertEquals("refund_pending", order.getStatus());
        verify(studyPassMapper, never()).insert(any(StudyPassEntity.class));
    }

    @Test
    void refundedSessionReplayCannotGrantAfterFirstPassExpires() {
        RechargeOrderEntity order = stubFulfillmentOrder();
        order.setStatus("refunded");
        assertFalse(service().fulfillStudyPassPayment(USER, PASS_CODE, "cs_refunded", "pi_refunded"));
        verify(studyPassMapper, never()).insert(any(StudyPassEntity.class));
    }

    @Test
    void freshCheckoutPersistsDurationSnapshot() throws Exception {
        stubUserLock();
        when(studyPassProductMapper.selectOne(any(Wrapper.class))).thenReturn(sellableProduct(99, 30));
        BillingDomainServiceImpl service = spy(service());
        setStripeSecretKey(service, "sk_test_study");
        Session session = new Session();
        session.setId("cs_new");
        session.setUrl("https://checkout.stripe.com/c/pay/cs_new");
        doReturn(session).when(service).createStripeCheckoutSession(any(SessionCreateParams.class));
        service.createStudyPassCheckout(USER, null, PASS_CODE,
                "http://localhost:3001/payment-success", "http://localhost:3001/payment-canceled", null);
        ArgumentCaptor<RechargeOrderEntity> order = ArgumentCaptor.forClass(RechargeOrderEntity.class);
        verify(rechargeOrderMapper).updateById(order.capture());
        assertEquals("{\"validityDays\":30}", order.getValue().getBizContext());
        assertEquals("study_pass", order.getValue().getOrderType());
        org.mockito.InOrder calls = org.mockito.Mockito.inOrder(userSubscriptionMapper, studyPassProductMapper);
        calls.verify(userSubscriptionMapper).selectByUserForUpdate(USER);
        calls.verify(studyPassProductMapper).selectOne(any(Wrapper.class));
    }

    @Test
    void repeatCheckoutReusesOpenSessionUnderUserLock() throws Exception {
        stubUserLock();
        when(studyPassProductMapper.selectOne(any(Wrapper.class))).thenReturn(sellableProduct(99, 30));
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setStripeSessionId("cs_pending");
        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        Session session = new Session();
        session.setId("cs_pending");
        session.setStatus("open");
        session.setUrl("https://checkout.stripe.com/c/pay/cs_pending");
        session.setExpiresAt(Instant.now().plusSeconds(1800).getEpochSecond());
        BillingDomainServiceImpl service = spy(service());
        setStripeSecretKey(service, "sk_test_study");
        doReturn(session).when(service).retrieveStripeCheckoutSession("cs_pending");
        assertEquals("cs_pending", service.createStudyPassCheckout(USER, null, PASS_CODE, null, null, null).getSessionId());
        org.mockito.InOrder calls = org.mockito.Mockito.inOrder(userSubscriptionMapper, rechargeOrderMapper);
        calls.verify(userSubscriptionMapper).selectByUserForUpdate(USER);
        calls.verify(rechargeOrderMapper).selectOne(any(Wrapper.class));
        verify(service, never()).createStripeCheckoutSession(any(SessionCreateParams.class));
    }

    @Test
    void paidButUnfulfilledCheckoutBlocksNewCheckout() throws Exception {
        stubUserLock();
        when(studyPassProductMapper.selectOne(any(Wrapper.class))).thenReturn(sellableProduct(99, 30));
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setStripeSessionId("cs_paid");
        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        Session session = new Session();
        session.setStatus("complete");
        BillingDomainServiceImpl service = spy(service());
        setStripeSecretKey(service, "sk_test_study");
        doReturn(session).when(service).retrieveStripeCheckoutSession("cs_paid");
        assertEquals("STUDY_PASS_PAYMENT_PENDING", assertThrows(BillingDomainException.class,
                () -> service.createStudyPassCheckout(USER, null, PASS_CODE, null, null, null)).getCode());
        verify(service, never()).createStripeCheckoutSession(any(SessionCreateParams.class));
    }

    private void stubFreeAccount() {
        when(userSubscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userRepository.findByClerkUserId(anyString())).thenReturn(Optional.empty());
        when(quotaVipAccessService.isQuotaVip(anyString())).thenReturn(false);
    }
}
