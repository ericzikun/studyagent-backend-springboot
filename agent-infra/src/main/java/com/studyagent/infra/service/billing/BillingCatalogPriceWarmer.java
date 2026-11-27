package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stripe.exception.StripeException;
import com.studyagent.infra.entity.AddonPackageDefEntity;
import com.studyagent.infra.entity.PricingZonePriceEntity;
import com.studyagent.infra.entity.StudyPassProductEntity;
import com.studyagent.infra.entity.SubscriptionPlanEntity;
import com.studyagent.infra.mapper.AddonPackageDefMapper;
import com.studyagent.infra.mapper.StudyPassProductMapper;
import com.studyagent.infra.mapper.SubscriptionPlanMapper;
import com.studyagent.service.domain.billing.IntroTrialPlans;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Warms catalog Prices off the request thread without resolving a user-specific catalog. */
@Slf4j
@Component
@RequiredArgsConstructor
class BillingCatalogPriceWarmer {
    private final StripePriceGateway prices;
    private final BillingPricingService pricingService;
    private final ZonePricingCatalog zonePricingCatalog;
    private final SubscriptionPlanMapper planMapper;
    private final AddonPackageDefMapper addonMapper;
    private final StudyPassProductMapper studyPassMapper;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            task -> new Thread(task, "billing-catalog-price-warmer"));

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (prices.isConfigured()) {
            worker.scheduleWithFixedDelay(this::warmPrices, 0, 1, TimeUnit.MINUTES);
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    void warmPrices() {
        try {
            for (String priceId : catalogPriceIds()) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                try {
                    prices.refreshIfDue(priceId);
                } catch (StripeException e) {
                    log.warn("Billing catalog Price refresh failed: priceId={}, error={}",
                            priceId, e.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Billing catalog Price warmup failed", e);
        }
    }

    private Set<String> catalogPriceIds() {
        Set<String> skuCodes = new LinkedHashSet<>();
        Set<String> priceIds = new LinkedHashSet<>();
        for (SubscriptionPlanEntity plan : planMapper.selectList(new LambdaQueryWrapper<SubscriptionPlanEntity>()
                .eq(SubscriptionPlanEntity::getIsActive, true))) {
            if (!IntroTrialPlans.isIntroTrialPlan(plan.getPlanCode(), plan.getOfferKind())
                    || IntroTrialPlans.isSellableIntroTrialPlanCode(plan.getPlanCode())) {
                skuCodes.add(plan.getPlanCode());
                priceIds.add(plan.getStripePriceId());
            }
        }
        for (AddonPackageDefEntity addon : addonMapper.selectList(new LambdaQueryWrapper<AddonPackageDefEntity>()
                .eq(AddonPackageDefEntity::getIsActive, true))) {
            skuCodes.add(addon.getAddonCode());
            priceIds.add(addon.getStripePriceId());
        }
        List<StudyPassProductEntity> passes = studyPassMapper.selectList(new LambdaQueryWrapper<StudyPassProductEntity>()
                .eq(StudyPassProductEntity::getIsActive, true)
                .orderByAsc(StudyPassProductEntity::getDisplayOrder));
        if (!passes.isEmpty()) {
            skuCodes.add(passes.get(0).getPassCode());
            priceIds.add(passes.get(0).getStripePriceId());
        }
        if (pricingService.isEnabled()) {
            addRegionalPrices(skuCodes, priceIds);
        }
        priceIds.removeIf(id -> id == null || id.isBlank());
        return priceIds;
    }

    private void addRegionalPrices(Set<String> skuCodes, Set<String> priceIds) {
        var catalog = zonePricingCatalog.snapshot();
        for (var zone : catalog.zones().values()) {
            if (!Boolean.TRUE.equals(zone.getIsActive()) || zone.getActiveVersion() == null) {
                continue;
            }
            var bindings = catalog.bindings().getOrDefault(zone.getZoneCode(), Map.of())
                    .getOrDefault(zone.getActiveVersion(), Map.of());
            for (PricingZonePriceEntity binding : bindings.values()) {
                if (!Boolean.TRUE.equals(binding.getIsActive()) || !skuCodes.contains(binding.getSkuCode())) {
                    continue;
                }
                priceIds.add(binding.getStripePriceId());
                String renewalSku = binding.getRenewalSkuCode();
                if (renewalSku != null && bindings.containsKey(renewalSku)) {
                    priceIds.add(bindings.get(renewalSku).getStripePriceId());
                }
            }
        }
    }
}
