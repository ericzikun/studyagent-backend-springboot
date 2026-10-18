package com.studyagent.infra.service.billing;

import com.studyagent.infra.entity.PricingCountryRouteEntity;
import com.studyagent.infra.entity.PricingZoneEntity;
import com.studyagent.infra.entity.PricingZonePriceEntity;
import com.studyagent.infra.entity.StripePriceAliasEntity;
import com.studyagent.infra.mapper.PricingCountryRouteMapper;
import com.studyagent.infra.mapper.PricingZoneMapper;
import com.studyagent.infra.mapper.PricingZonePriceMapper;
import com.studyagent.infra.mapper.StripePriceAliasMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 区域定价注册表：装载/门控/反查/结构校验（plan-a-db §9 ZonePricingCatalogTest）。
 * 合同解析不变量：反查与合同绑定解析不看任何开关、不看 is_active——已售合同必须永远可解析。
 */
@ExtendWith(MockitoExtension.class)
class ZonePricingCatalogTest {

    private static final String ZONE = "Z080";
    private static final String VERSION = "regional-20261018-v1";

    @Mock
    private PricingZoneMapper zoneMapper;
    @Mock
    private PricingCountryRouteMapper routeMapper;
    @Mock
    private PricingZonePriceMapper priceMapper;
    @Mock
    private StripePriceAliasMapper aliasMapper;

    @Test
    void enabledRouteNormalizesCountryAndRequiresEnabledFlag() {
        load(List.of(zone(true)), List.of(route("DE", true), route("it", false)), List.of(), List.of());
        ZonePricingCatalog catalog = catalog();

        // 小写请求国家仍命中大写键；is_enabled=0 的国家回落 legacy。
        Optional<PricingCountryRouteEntity> german = catalog.enabledRoute("de");
        assertTrue(german.isPresent());
        assertEquals("eur", german.get().getCurrency());
        assertTrue(catalog.enabledRoute("IT").isEmpty());
        assertTrue(catalog.enabledRoute("US").isEmpty());
        assertTrue(catalog.enabledRoute(null).isEmpty());
        assertTrue(catalog.enabledRoute("  ").isEmpty());
    }

    @Test
    void activeBindingFollowsZoneActiveVersionAndSkipsInactiveRows() {
        PricingZonePriceEntity active = binding("pro_monthly", "price_pro", true, 2099);
        PricingZonePriceEntity retired = binding("addon_humanizer_3", "price_humanizer", false, 499);
        load(List.of(zone(true)), List.of(), List.of(active, retired), List.of());
        ZonePricingCatalog catalog = catalog();

        assertTrue(catalog.activeBinding(ZONE, "pro_monthly").isPresent());
        // 停售（is_active=0）只拦新购；合同解析必须继续可见。
        assertTrue(catalog.activeBinding(ZONE, "addon_humanizer_3").isEmpty());
        assertTrue(catalog.contractBinding(ZONE, VERSION, "addon_humanizer_3").isPresent());
        assertTrue(catalog.contractBinding(ZONE, "regional-19990101-v0", "pro_monthly").isEmpty());
        assertTrue(catalog.activeBinding("Z999", "pro_monthly").isEmpty());
    }

    @Test
    void bindingByIdResolvesSoldContractsEvenWhenZoneIsDeactivated() {
        // 回滚安全不变量：关区开关后已售合同的 Price 反查仍然可用。
        load(List.of(zone(false)), List.of(), List.of(binding("pro_monthly", "price_pro", true, 2099)), List.of());
        ZonePricingCatalog catalog = catalog();

        Optional<PricingZonePriceEntity> resolved = catalog.bindingById("price_pro");
        assertTrue(resolved.isPresent());
        assertEquals("pro_monthly", resolved.get().getSkuCode());
        assertTrue(catalog.bindingById("  ").isEmpty());
        assertTrue(catalog.bindingById(null).isEmpty());
        assertTrue(catalog.bindingById("price_unknown").isEmpty());
        assertEquals(Optional.empty(), catalog.alias("price_pro"));
    }

    @Test
    void aliasLookupAnswersForLegacyPriceIds() {
        StripePriceAliasEntity alias = new StripePriceAliasEntity();
        alias.setStripePriceId("price_legacy_pro");
        alias.setSkuCode("pro_monthly");
        alias.setSkuKind("subscription_plan");
        load(List.of(zone(true)), List.of(), List.of(), List.of(alias));
        ZonePricingCatalog catalog = catalog();

        assertEquals(Optional.of("pro_monthly"), catalog.alias("price_legacy_pro").map(StripePriceAliasEntity::getSkuCode));
        assertTrue(catalog.alias("price_other").isEmpty());
    }

    @Test
    void currencyAmountsParsesJsonCaseInsensitivelyAndRejectsGarbage() {
        PricingZonePriceEntity binding = binding("pro_monthly", "price_pro", true, 2099);
        binding.setCurrencyAmounts("{\"EUR\":{\"amountMinor\":1399},\"hkd\":{\"amountMinor\":11500}}");
        load(List.of(zone(true)), List.of(), List.of(binding), List.of());
        ZonePricingCatalog catalog = catalog();

        Map<String, Integer> amounts = catalog.currencyAmounts(catalog.bindingById("price_pro").orElseThrow()).orElseThrow();
        assertEquals(1399, amounts.get("eur"));
        assertEquals(11500, amounts.get("hkd"));

        binding.setCurrencyAmounts("not-json");
        assertTrue(catalog.currencyAmounts(binding).isEmpty());
        binding.setCurrencyAmounts(null);
        assertTrue(catalog.currencyAmounts(binding).isEmpty());
        assertTrue(catalog.currencyAmounts(null).isEmpty());
    }

    @Test
    void structuralViolationsAcceptCompleteEnabledZone() {
        List<PricingZonePriceEntity> complete = new ArrayList<>();
        for (String sku : ZonePricingCatalog.REQUIRED_SKUS) {
            complete.add(binding(sku, "price_" + sku, true, 1999));
        }
        load(List.of(zone(true)), List.of(route("DE", true)), complete, List.of());

        assertEquals(List.of(), catalog().structuralViolations());
    }

    @Test
    void structuralViolationsFlagIncompleteEnabledZoneButIgnoreDisabledCountries() {
        // 缺 study_pass SKU 且 pro 绑定未回填 Price ID：启用国家必须齐备才可开。
        List<PricingZonePriceEntity> incomplete = new ArrayList<>(ZonePricingCatalog.REQUIRED_SKUS.stream()
                .filter(sku -> !"study_pass_subscription_30d".equals(sku))
                .map(sku -> binding(sku, "price_" + sku, true, 1999))
                .toList());
        PricingZonePriceEntity draft = binding("study_pass_subscription_30d", null, true, 199);
        incomplete.add(draft);
        load(List.of(zone(true)), List.of(route("DE", true), route("FR", false)), incomplete, List.of());

        List<String> violations = catalog().structuralViolations();
        assertTrue(violations.stream().anyMatch(v -> v.contains("country DE") && v.contains("study_pass_subscription_30d(no-price-id)")));
        // 未启用国家不参与齐备性校验。
        assertFalse(violations.stream().anyMatch(v -> v.contains("country FR")));
    }

    @Test
    void structuralViolationsFlagZoneSwitchAndBindingFieldProblems() {
        // 国家启用但区开关未开 = 硬违规。
        PricingZonePriceEntity badInterval = binding("addon_assignment_3", "price_addon", true, 999);
        badInterval.setPriceType("one_time");
        badInterval.setBillingInterval("month");
        PricingZonePriceEntity missingInterval = binding("pro_yearly", "price_pro_yearly", true, 79999);
        missingInterval.setPriceType("recurring");
        missingInterval.setBillingInterval(null);
        PricingZonePriceEntity danglingRenewal = binding("pro_trial_to_monthly", "price_trial", true, 199);
        danglingRenewal.setRenewalSkuCode("pro_monthly");
        PricingZonePriceEntity placeholder = binding("basic_monthly", "placeholder_basic", true, 1999);
        load(List.of(zone(false)), List.of(route("DE", true)),
                List.of(badInterval, missingInterval, danglingRenewal, placeholder), List.of());

        List<String> violations = catalog().structuralViolations();
        assertTrue(violations.stream().anyMatch(v -> v.contains("enabled but zone Z080 is inactive")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("one_time binding has interval")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("recurring binding missing interval")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("renewal target pro_monthly not in same version")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("placeholder price id")));
    }

    @Test
    void structuralViolationsFlagUnknownZoneRoutesAndPriceIdOverlap() {
        StripePriceAliasEntity overlap = new StripePriceAliasEntity();
        overlap.setStripePriceId("price_pro");
        overlap.setSkuCode("pro_monthly");
        overlap.setSkuKind("subscription_plan");
        PricingCountryRouteEntity unknownZone = route("XX", true);
        unknownZone.setZoneCode("Z999");
        load(List.of(zone(true)), List.of(unknownZone), List.of(binding("pro_monthly", "price_pro", true, 2099)), List.of(overlap));

        List<String> violations = catalog().structuralViolations();
        assertTrue(violations.stream().anyMatch(v -> v.contains("references unknown zone")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("both registry and aliases")));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private ZonePricingCatalog catalog() {
        return new ZonePricingCatalog(zoneMapper, routeMapper, priceMapper, aliasMapper, 60);
    }

    private void load(List<PricingZoneEntity> zones, List<PricingCountryRouteEntity> routes,
                      List<PricingZonePriceEntity> bindings, List<StripePriceAliasEntity> aliases) {
        when(zoneMapper.selectList(any())).thenReturn(zones);
        when(routeMapper.selectList(any())).thenReturn(routes);
        when(priceMapper.selectList(any())).thenReturn(bindings);
        when(aliasMapper.selectList(any())).thenReturn(aliases);
    }

    private static PricingZoneEntity zone(boolean active) {
        PricingZoneEntity entity = new PricingZoneEntity();
        entity.setZoneCode(ZONE);
        entity.setLabel("测试区");
        entity.setReferenceFactor("0.80");
        entity.setActiveVersion(VERSION);
        entity.setIsActive(active);
        return entity;
    }

    private static PricingCountryRouteEntity route(String country, boolean enabled) {
        PricingCountryRouteEntity entity = new PricingCountryRouteEntity();
        entity.setCountryCode(country);
        entity.setZoneCode(ZONE);
        entity.setCurrency("eur");
        entity.setLabel(country);
        entity.setIsEnabled(enabled);
        return entity;
    }

    private static PricingZonePriceEntity binding(String sku, String priceId, boolean active, int usdDefault) {
        PricingZonePriceEntity entity = new PricingZonePriceEntity();
        entity.setZoneCode(ZONE);
        entity.setPriceVersion(VERSION);
        entity.setSkuCode(sku);
        entity.setSkuKind(sku.startsWith("addon_") ? "addon"
                : "study_pass_subscription_30d".equals(sku) ? "study_pass" : "subscription_plan");
        entity.setStripePriceId(priceId);
        entity.setStripeProductId("prod_" + sku);
        entity.setPriceType(sku.startsWith("addon_") ? "one_time" : "recurring");
        entity.setBillingInterval(sku.startsWith("addon_") ? null : "month");
        entity.setIntervalCount(1);
        entity.setUsdDefaultAmountMinor(usdDefault);
        entity.setCurrencyAmounts("{\"eur\":{\"amountMinor\":1399}}");
        entity.setIsActive(active);
        return entity;
    }
}
