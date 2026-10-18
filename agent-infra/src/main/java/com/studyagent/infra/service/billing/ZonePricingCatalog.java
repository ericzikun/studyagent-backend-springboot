package com.studyagent.infra.service.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.studyagent.infra.entity.PricingCountryRouteEntity;
import com.studyagent.infra.entity.PricingZoneEntity;
import com.studyagent.infra.entity.PricingZonePriceEntity;
import com.studyagent.infra.entity.StripePriceAliasEntity;
import com.studyagent.infra.mapper.PricingCountryRouteMapper;
import com.studyagent.infra.mapper.PricingZoneMapper;
import com.studyagent.infra.mapper.PricingZonePriceMapper;
import com.studyagent.infra.mapper.StripePriceAliasMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 区域定价目录的只读快照：zones / country routes / Price 注册表 / 别名。
 *
 * <p>结构校验只查数据库（离线）；远程 Stripe 交叉核对走 preflight。短缓存 TTL 保证
 * 回填 Price ID、启用国家等 SQL 变更在 TTL 内生效，无需重启。开关只门控新购选价，
 * 注册表反查（webhook、合同解析、rollout）必须绕过开关使用 {@link #bindingById}。</p>
 */
@Slf4j
@Service
public class ZonePricingCatalog {

    /** 首批每区必须齐备的可售 SKU（启用该区任一国家前）。 */
    public static final Set<String> REQUIRED_SKUS = Set.of(
            "pro_trial_to_monthly",
            "basic_monthly", "basic_yearly",
            "plus_monthly", "plus_yearly",
            "pro_monthly", "pro_yearly",
            "addon_assignment_3", "addon_detection_5", "addon_humanizer_3",
            "study_pass_subscription_30d");

    public record Snapshot(
            Map<String, PricingZoneEntity> zones,
            Map<String, PricingCountryRouteEntity> routes,
            Map<String, Map<String, Map<String, PricingZonePriceEntity>>> bindings,
            Map<String, PricingZonePriceEntity> bindingsByPriceId,
            Map<String, StripePriceAliasEntity> aliases) {
    }

    private final PricingZoneMapper pricingZoneMapper;
    private final PricingCountryRouteMapper routeMapper;
    private final PricingZonePriceMapper priceMapper;
    private final StripePriceAliasMapper aliasMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Cache<String, Snapshot> cache;

    public ZonePricingCatalog(
            PricingZoneMapper pricingZoneMapper,
            PricingCountryRouteMapper routeMapper,
            PricingZonePriceMapper priceMapper,
            StripePriceAliasMapper aliasMapper,
            @Value("${billing.regional-pricing.zone-pricing.catalog-cache-seconds:60}") long cacheSeconds) {
        this.pricingZoneMapper = pricingZoneMapper;
        this.routeMapper = routeMapper;
        this.priceMapper = priceMapper;
        this.aliasMapper = aliasMapper;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(Math.max(1, cacheSeconds)))
                .maximumSize(1)
                .build();
    }

    public Snapshot snapshot() {
        return cache.get("catalog", key -> load());
    }

    /** 缓存失效（preflight / 回填后调用，立即看到最新数据）。 */
    public void invalidate() {
        cache.invalidateAll();
    }

    private Snapshot load() {
        try {
            Map<String, PricingZoneEntity> zones = new LinkedHashMap<>();
            for (PricingZoneEntity zone : safeList(pricingZoneMapper.selectList(null))) {
                zones.put(zone.getZoneCode(), zone);
            }
            Map<String, PricingCountryRouteEntity> routes = new LinkedHashMap<>();
            for (PricingCountryRouteEntity route : safeList(routeMapper.selectList(null))) {
                if (route.getCountryCode() != null) {
                    routes.put(route.getCountryCode().trim().toUpperCase(Locale.ROOT), route);
                }
            }
            Map<String, Map<String, Map<String, PricingZonePriceEntity>>> bindings = new LinkedHashMap<>();
            Map<String, PricingZonePriceEntity> byPriceId = new HashMap<>();
            for (PricingZonePriceEntity row : safeList(priceMapper.selectList(null))) {
                bindings
                        .computeIfAbsent(nullSafe(row.getZoneCode()), z -> new LinkedHashMap<>())
                        .computeIfAbsent(nullSafe(row.getPriceVersion()), v -> new LinkedHashMap<>())
                        .put(nullSafe(row.getSkuCode()), row);
                if (row.getStripePriceId() != null && !row.getStripePriceId().isBlank()) {
                    byPriceId.put(row.getStripePriceId().trim(), row);
                }
            }
            Map<String, StripePriceAliasEntity> aliases = new LinkedHashMap<>();
            for (StripePriceAliasEntity alias : safeList(aliasMapper.selectList(null))) {
                if (alias.getStripePriceId() != null) {
                    aliases.put(alias.getStripePriceId().trim(), alias);
                }
            }
            Snapshot snapshot = new Snapshot(zones, routes, bindings, byPriceId, aliases);
            List<String> violations = structuralViolations(snapshot);
            if (!violations.isEmpty()) {
                log.warn("Zone pricing catalog structural violations ({}): {}",
                        violations.size(), violations);
            }
            return snapshot;
        } catch (org.springframework.dao.DataAccessException e) {
            // 迁移未执行/表结构不符时安全降级为 legacy，避免开关默认开启后计费接口整体 500。
            log.error("Zone pricing catalog unavailable, falling back to legacy pricing: {}", e.toString());
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    // ------------------------------------------------------------------
    // 查询入口
    // ------------------------------------------------------------------

    /** 只返回已启用国家的路由；未启用/未知国家返回 empty，调用方回落 legacy。 */
    public Optional<PricingCountryRouteEntity> enabledRoute(String country) {
        if (country == null || country.isBlank()) {
            return Optional.empty();
        }
        PricingCountryRouteEntity route =
                snapshot().routes().get(country.trim().toUpperCase(Locale.ROOT));
        return route != null && Boolean.TRUE.equals(route.getIsEnabled())
                ? Optional.of(route)
                : Optional.empty();
    }

    public Optional<PricingZoneEntity> zone(String zoneCode) {
        if (zoneCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot().zones().get(zoneCode.trim()));
    }

    /** 当前销售版本的正查（仅 is_active=1 的绑定）。 */
    public Optional<PricingZonePriceEntity> activeBinding(String zoneCode, String skuCode) {
        PricingZoneEntity zone = zone(zoneCode).orElse(null);
        if (zone == null) {
            return Optional.empty();
        }
        return binding(zoneCode, zone.getActiveVersion(), skuCode);
    }

    public Optional<PricingZonePriceEntity> binding(String zoneCode, String version, String skuCode) {
        return contractBinding(zoneCode, version, skuCode)
                .filter(row -> Boolean.TRUE.equals(row.getIsActive()));
    }

    /**
     * 合同解析用：不看 is_active。注册表行停售（is_active=0）只拦新购，
     * 存量合同仍停在该版本上，会员动作与换价必须能继续解析。
     */
    public Optional<PricingZonePriceEntity> contractBinding(String zoneCode, String version, String skuCode) {
        PricingZonePriceEntity row = snapshot()
                .bindings()
                .getOrDefault(nullSafe(zoneCode), Map.of())
                .getOrDefault(nullSafe(version), Map.of())
                .get(nullSafe(skuCode));
        return Optional.ofNullable(row);
    }

    /**
     * 反查：Price ID → 注册表行。webhook / 合同解析入口，不看任何开关。
     */
    public Optional<PricingZonePriceEntity> bindingById(String stripePriceId) {
        if (stripePriceId == null || stripePriceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot().bindingsByPriceId().get(stripePriceId.trim()));
    }

    public Optional<StripePriceAliasEntity> alias(String stripePriceId) {
        if (stripePriceId == null || stripePriceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot().aliases().get(stripePriceId.trim()));
    }

    /** 解析 currency_amounts JSON → 币种(小写) → 最小单位金额。非法 JSON 返回 empty。 */
    public Optional<Map<String, Integer>> currencyAmounts(PricingZonePriceEntity binding) {
        if (binding == null || binding.getCurrencyAmounts() == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(binding.getCurrencyAmounts());
            Map<String, Integer> result = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                JsonNode minor = entry.getValue().get("amountMinor");
                if (minor != null && minor.canConvertToInt()) {
                    result.put(entry.getKey().trim().toLowerCase(Locale.ROOT), minor.asInt());
                }
            });
            return result.isEmpty() ? Optional.empty() : Optional.of(result);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // 结构校验（离线，只查 DB）
    // ------------------------------------------------------------------

    public List<String> structuralViolations() {
        return structuralViolations(snapshot());
    }

    static List<String> structuralViolations(Snapshot snapshot) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, PricingCountryRouteEntity> entry : snapshot.routes().entrySet()) {
            PricingCountryRouteEntity route = entry.getValue();
            PricingZoneEntity zone = snapshot.zones().get(route.getZoneCode());
            if (zone == null) {
                violations.add("route " + entry.getKey() + " references unknown zone " + route.getZoneCode());
                continue;
            }
            if (!Boolean.TRUE.equals(route.getIsEnabled())) {
                continue;
            }
            if (!Boolean.TRUE.equals(zone.getIsActive())) {
                violations.add("country " + entry.getKey() + " enabled but zone " + zone.getZoneCode()
                        + " is inactive");
            }
            violations.addAll(skuCompleteness(snapshot, zone, "country " + entry.getKey()));
        }
        // 注册表与别名的 Price ID 冲突
        Set<String> overlap = new LinkedHashSet<>(snapshot.bindingsByPriceId().keySet());
        overlap.retainAll(snapshot.aliases().keySet());
        if (!overlap.isEmpty()) {
            violations.add("price ids present in both registry and aliases: "
                    + overlap.stream().limit(5).collect(Collectors.joining(",")));
        }
        // 逐绑定字段自洽
        for (PricingZonePriceEntity row : snapshot.bindingsByPriceId().values()) {
            violations.addAll(bindingViolations(snapshot, row));
        }
        return violations;
    }

    private static List<String> skuCompleteness(Snapshot snapshot, PricingZoneEntity zone, String scope) {
        Map<String, PricingZonePriceEntity> version =
                snapshot.bindings().getOrDefault(zone.getZoneCode(), Map.of())
                        .getOrDefault(zone.getActiveVersion(), Map.of());
        List<String> missing = new ArrayList<>();
        for (String sku : REQUIRED_SKUS) {
            PricingZonePriceEntity row = version.get(sku);
            if (row == null || !Boolean.TRUE.equals(row.getIsActive())) {
                missing.add(sku + "(missing)");
            } else if (row.getStripePriceId() == null || row.getStripePriceId().isBlank()) {
                missing.add(sku + "(no-price-id)");
            }
        }
        if (missing.isEmpty()) {
            return List.of();
        }
        return List.of(scope + " zone " + zone.getZoneCode() + "@" + zone.getActiveVersion()
                + " incomplete SKUs: " + String.join(",", missing));
    }

    private static List<String> bindingViolations(Snapshot snapshot, PricingZonePriceEntity row) {
        List<String> violations = new ArrayList<>();
        String id = row.getZoneCode() + "@" + row.getPriceVersion() + "/" + row.getSkuCode();
        boolean recurring = "recurring".equals(row.getPriceType());
        boolean oneTime = "one_time".equals(row.getPriceType());
        if (!recurring && !oneTime) {
            violations.add(id + " invalid price_type " + row.getPriceType());
        }
        if (recurring && (row.getBillingInterval() == null || row.getIntervalCount() == null
                || row.getIntervalCount() < 1)) {
            violations.add(id + " recurring binding missing interval");
        }
        if (oneTime && row.getBillingInterval() != null) {
            violations.add(id + " one_time binding has interval");
        }
        if (row.getUsdDefaultAmountMinor() == null || row.getUsdDefaultAmountMinor() < 0) {
            violations.add(id + " invalid usd default amount");
        }
        if (row.getStripePriceId() == null || row.getStripePriceId().isBlank()) {
            violations.add(id + " draft row (no price id)"); // 仅在启用范围内才是硬违规
        } else if (row.getStripePriceId().startsWith("placeholder") || row.getStripePriceId().contains("TODO")) {
            violations.add(id + " placeholder price id");
        }
        if (row.getRenewalSkuCode() != null) {
            boolean targetPresent = snapshot
                    .bindings()
                    .getOrDefault(nullSafe(row.getZoneCode()), Map.of())
                    .getOrDefault(nullSafe(row.getPriceVersion()), Map.of())
                    .containsKey(row.getRenewalSkuCode());
            if (!targetPresent) {
                violations.add(id + " renewal target " + row.getRenewalSkuCode() + " not in same version");
            }
        }
        return violations;
    }

    private static <T> List<T> safeList(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }
}
