package com.studyagent.infra.service.billing;

import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.studyagent.infra.entity.PricingZonePriceEntity;
import com.studyagent.infra.entity.StripePriceAliasEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 区域定价上线前检（只读）：注册表绑定对 Stripe 逐项交叉核对。
 *
 * <p>每个绑定核对 livemode / product / price_type / 周期 / USD 技术默认值 /
 * currency_options 逐币种双向比对（缺币种、金额不等、多出未注册币种都算失败）；
 * 别名表核对 Price 存在性与 livemode。草稿行（price_id 未回填）单独计数不算失败。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZonePricingPreflightService {

    private final ZonePricingCatalog catalog;
    private final StripePriceGateway priceGateway;

    public Result run(String zoneFilter, boolean fresh) {
        catalog.invalidate();
        ZonePricingCatalog.Snapshot snapshot = catalog.snapshot();

        List<Map<String, Object>> items = new ArrayList<>();
        int pass = 0;
        int fail = 0;
        int draft = 0;
        int stripeError = 0;
        for (PricingZonePriceEntity row : sortedBindings(snapshot, zoneFilter)) {
            Map<String, Object> item = checkBinding(row, fresh);
            items.add(item);
            switch (String.valueOf(item.get("status"))) {
                case "pass" -> pass++;
                case "fail" -> fail++;
                case "draft" -> draft++;
                default -> stripeError++;
            }
        }
        for (StripePriceAliasEntity alias : new TreeMap<>(snapshot.aliases()).values()) {
            Map<String, Object> item = checkAlias(alias, fresh);
            items.add(item);
            if ("pass".equals(item.get("status"))) {
                pass++;
            } else {
                fail++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", items.size());
        summary.put("pass", pass);
        summary.put("fail", fail);
        summary.put("draft", draft);
        summary.put("stripeError", stripeError);
        summary.put("ok", fail == 0 && stripeError == 0 && draft == 0);
        log.info("zone pricing preflight: {}", summary);
        return new Result(
                priceGateway.isLive() ? "live" : "test",
                zoneFilter == null || zoneFilter.isBlank() ? null : zoneFilter.trim().toUpperCase(Locale.ROOT),
                catalog.structuralViolations(),
                summary,
                items);
    }

    public record Result(
            String stripeMode,
            String zoneFilter,
            List<String> structuralViolations,
            Map<String, Object> summary,
            List<Map<String, Object>> items) {
    }

    private static List<PricingZonePriceEntity> sortedBindings(
            ZonePricingCatalog.Snapshot snapshot, String zoneFilter) {
        String zone = zoneFilter == null || zoneFilter.isBlank()
                ? null
                : zoneFilter.trim().toUpperCase(Locale.ROOT);
        List<PricingZonePriceEntity> rows = new ArrayList<>();
        snapshot.bindings().forEach((zoneCode, versions) -> {
            if (zone != null && !zone.equals(zoneCode)) {
                return;
            }
            versions.values().forEach(skuMap -> skuMap.values().forEach(row -> {
                if (row != null && Boolean.TRUE.equals(row.getIsActive())) {
                    rows.add(row);
                }
            }));
        });
        rows.sort(java.util.Comparator
                .comparing((PricingZonePriceEntity r) -> nullToEmpty(r.getZoneCode()))
                .thenComparing(r -> nullToEmpty(r.getSkuCode())));
        return rows;
    }

    private Map<String, Object> checkBinding(PricingZonePriceEntity row, boolean fresh) {
        Map<String, Object> item = baseItem(
                row.getZoneCode() + "@" + row.getPriceVersion() + "/" + row.getSkuCode(),
                row.getStripePriceId());
        if (row.getStripePriceId() == null || row.getStripePriceId().isBlank()) {
            item.put("status", "draft");
            item.put("issues", List.of("stripe_price_id not backfilled"));
            return item;
        }
        List<String> issues = new ArrayList<>();
        try {
            Price price = priceGateway.retrieve(row.getStripePriceId(), fresh);
            if (!Objects.equals(priceGateway.isLive(), Boolean.TRUE.equals(price.getLivemode()))) {
                issues.add("livemode mismatch");
            }
            if (!Boolean.TRUE.equals(price.getActive())) {
                issues.add("price is archived/inactive");
            }
            if (row.getStripeProductId() != null && !row.getStripeProductId().isBlank()
                    && !Objects.equals(row.getStripeProductId(), price.getProduct())) {
                issues.add("product mismatch: stripe=" + price.getProduct());
            }
            boolean recurring = "recurring".equals(row.getPriceType());
            if (recurring) {
                if (price.getRecurring() == null) {
                    issues.add("expected recurring price, stripe has none");
                } else {
                    if (row.getBillingInterval() != null
                            && !row.getBillingInterval().equals(price.getRecurring().getInterval())) {
                        issues.add("interval mismatch: stripe=" + price.getRecurring().getInterval());
                    }
                    long expectedCount = row.getIntervalCount() == null ? 1L : row.getIntervalCount();
                    if (expectedCount != nullSafeLong(price.getRecurring().getIntervalCount())) {
                        issues.add("interval_count mismatch: stripe=" + price.getRecurring().getIntervalCount());
                    }
                }
            } else if (price.getRecurring() != null) {
                issues.add("expected one_time price, stripe is recurring");
            }

            // 基准币种必须是 USD 且等于技术默认值（spec：USD 仅技术兜底，不可售）
            if (!"usd".equalsIgnoreCase(price.getCurrency())) {
                issues.add("base currency is " + price.getCurrency() + ", expected usd");
            } else if (nullSafeLong(price.getUnitAmount()) != nullSafeLong(row.getUsdDefaultAmountMinor())) {
                issues.add("usd default mismatch: stripe=" + price.getUnitAmount()
                        + " registry=" + row.getUsdDefaultAmountMinor());
            }

            Map<String, Integer> expected = catalog.currencyAmounts(row).orElse(Map.of());
            Map<String, Price.CurrencyOption> options = price.getCurrencyOptions() == null
                    ? Map.of()
                    : price.getCurrencyOptions();
            for (Map.Entry<String, Integer> entry : expected.entrySet()) {
                Price.CurrencyOption option = options.get(entry.getKey());
                if (option == null) {
                    issues.add("missing currency_option " + entry.getKey());
                } else if (nullSafeLong(option.getUnitAmount()) != nullSafeLong(entry.getValue())) {
                    issues.add("amount mismatch " + entry.getKey() + ": stripe=" + option.getUnitAmount()
                            + " registry=" + entry.getValue());
                }
            }
            for (String currency : options.keySet()) {
                if (!expected.containsKey(currency)) {
                    issues.add("unexpected currency_option " + currency);
                }
            }
            item.put("status", issues.isEmpty() ? "pass" : "fail");
        } catch (StripeException ex) {
            item.put("status", "stripe_error");
            issues.add("stripe retrieve failed: " + ex.getMessage());
        }
        item.put("issues", issues);
        return item;
    }

    private Map<String, Object> checkAlias(StripePriceAliasEntity alias, boolean fresh) {
        Map<String, Object> item = baseItem("alias/" + alias.getSkuCode(), alias.getStripePriceId());
        List<String> issues = new ArrayList<>();
        try {
            Price price = priceGateway.retrieve(alias.getStripePriceId(), fresh);
            if (!Objects.equals(priceGateway.isLive(), Boolean.TRUE.equals(price.getLivemode()))) {
                issues.add("livemode mismatch");
            }
            item.put("status", issues.isEmpty() ? "pass" : "fail");
        } catch (StripeException ex) {
            item.put("status", "fail");
            issues.add("stripe retrieve failed: " + ex.getMessage());
        }
        item.put("issues", issues);
        return item;
    }

    private static Map<String, Object> baseItem(String binding, String priceId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("binding", binding);
        item.put("priceId", priceId);
        return item;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static long nullSafeLong(Number value) {
        return value == null ? Long.MIN_VALUE : value.longValue();
    }
}
