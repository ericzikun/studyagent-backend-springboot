package com.studyagent.infra.service.billing;

import com.stripe.exception.ApiConnectionException;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.PricingZoneEntity;
import com.studyagent.infra.entity.PricingZonePriceEntity;
import com.studyagent.infra.entity.StudyPassProductEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.StudyPassProductMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BillingCatalogPriceWarmerTest {
    @Test
    void startupWarmsCatalogAndRegionalRenewalOffCallerThread() throws Exception {
        var prices = mock(StripePriceGateway.class);
        var pricing = mock(BillingPricingService.class);
        var catalog = mock(ZonePricingCatalog.class);
        var plans = mock(SubscriptionPlanMapper.class);
        var addons = mock(AddonPackageDefMapper.class);
        var passes = mock(StudyPassProductMapper.class);
        when(prices.isConfigured()).thenReturn(true);
        when(pricing.isEnabled()).thenReturn(true);
        when(plans.selectList(any())).thenReturn(List.of(
                plan("pro_trial_to_monthly", "price_legacy_trial"),
                plan("pro_trial_to_yearly", "price_retired")));
        var addon = new AddonPackageDefEntity();
        addon.setAddonCode("addon_assignment_3");
        addon.setStripePriceId("price_addon");
        when(addons.selectList(any())).thenReturn(List.of(addon));
        var pass = new StudyPassProductEntity();
        pass.setPassCode("study_pass_subscription_30d");
        pass.setStripePriceId("price_pass");
        when(passes.selectList(any())).thenReturn(List.of(pass));

        var zone = new PricingZoneEntity();
        zone.setZoneCode("Z100");
        zone.setActiveVersion("v2");
        zone.setIsActive(true);
        var trial = binding("pro_trial_to_monthly", "price_zone_trial");
        trial.setRenewalSkuCode("pro_monthly");
        var renewal = binding("pro_monthly", "price_zone_renewal");
        var zoneAddon = binding("addon_assignment_3", "price_addon");
        when(catalog.snapshot()).thenReturn(new ZonePricingCatalog.Snapshot(
                Map.of("Z100", zone), Map.of(), Map.of("Z100", Map.of(
                        "v1", Map.of("pro_trial_to_monthly", binding("pro_trial_to_monthly", "price_historical")),
                        "v2", Map.of(trial.getSkuCode(), trial, renewal.getSkuCode(), renewal,
                                zoneAddon.getSkuCode(), zoneAddon))), Map.of(), Map.of()));

        doThrow(new ApiConnectionException("Stripe unavailable")).when(prices).refreshIfDue("price_legacy_trial");
        CountDownLatch refreshed = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        doAnswer(call -> {
            worker.set(Thread.currentThread());
            refreshed.countDown();
            return null;
        }).when(prices).refreshIfDue("price_zone_renewal");

        var warmer = new BillingCatalogPriceWarmer(prices, pricing, catalog, plans, addons, passes);
        try {
            warmer.start();
            assertTrue(refreshed.await(3, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), worker.get());
            verify(prices).isConfigured();
            verify(prices).refreshIfDue("price_legacy_trial");
            verify(prices).refreshIfDue("price_addon");
            verify(prices).refreshIfDue("price_pass");
            verify(prices).refreshIfDue("price_zone_trial");
            verify(prices).refreshIfDue("price_zone_renewal");
            verifyNoMoreInteractions(prices);
        } finally {
            warmer.stop();
        }
    }

    private static SubscriptionPlanEntity plan(String code, String priceId) {
        var plan = new SubscriptionPlanEntity();
        plan.setPlanCode(code);
        plan.setStripePriceId(priceId);
        return plan;
    }

    private static PricingZonePriceEntity binding(String sku, String priceId) {
        var binding = new PricingZonePriceEntity();
        binding.setSkuCode(sku);
        binding.setStripePriceId(priceId);
        binding.setIsActive(true);
        return binding;
    }
}
