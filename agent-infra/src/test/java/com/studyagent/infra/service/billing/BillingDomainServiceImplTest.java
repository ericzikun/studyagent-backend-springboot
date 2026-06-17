package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.infra.mapper.UserSubscriptionMapper;
import com.studyagent.service.domain.billing.BillingDomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingDomainServiceImplTest {
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
        assertEquals("addon_assignment_3", result.getAddons().get(0).getAddonCode());
        assertEquals(2, result.getAddons().get(0).getValidityMonths());
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
    void checkoutReturnUrlsPreferSafeOverrides() {
        BillingDomainServiceImpl service = serviceWithReturnUrls();

        assertEquals(
                "https://staging.verla.io/payment-success?return_to=%2Fpricing&session_id={CHECKOUT_SESSION_ID}",
                service.resolveCheckoutSuccessUrl("https://staging.verla.io/payment-success?return_to=%2Fpricing"));
        assertEquals(
                "http://localhost:3001/payment-canceled?return_to=%2Fpricing",
                service.resolveCheckoutCancelUrl("http://localhost:3001/payment-canceled?return_to=%2Fpricing"));
    }

    @Test
    void checkoutReturnUrlsFallbackToConfiguredUrlsWhenOverridesAreBlank() {
        BillingDomainServiceImpl service = serviceWithReturnUrls();

        assertEquals(
                "https://verla.io/payment-success?session_id={CHECKOUT_SESSION_ID}",
                service.resolveCheckoutSuccessUrl(null));
        assertEquals(
                "https://verla.io/payment-canceled",
                service.resolveCheckoutCancelUrl(" "));
    }

    @Test
    void checkoutReturnUrlsRejectUnsafeOrigins() {
        BillingDomainServiceImpl service = serviceWithReturnUrls();

        assertThrows(
                BillingDomainException.class,
                () -> service.resolveCheckoutSuccessUrl("https://evil.test/payment-success"));
        assertThrows(
                BillingDomainException.class,
                () -> service.resolveCheckoutCancelUrl("http://verla.io/payment-canceled"));
    }

    private BillingDomainServiceImpl serviceWithReturnUrls() {
        BillingDomainServiceImpl service = service();
        ReflectionTestUtils.setField(service, "successUrl", "https://verla.io/payment-success");
        ReflectionTestUtils.setField(service, "cancelUrl", "https://verla.io/payment-canceled");
        return service;
    }

    private BillingDomainServiceImpl service() {
        return new BillingDomainServiceImpl(
                subscriptionPlanMapper,
                addonPackageDefMapper,
                userSubscriptionMapper,
                rechargeOrderMapper);
    }
}
