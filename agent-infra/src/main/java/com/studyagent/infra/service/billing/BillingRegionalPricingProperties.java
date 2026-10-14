package com.studyagent.infra.service.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 服务端新购选币规则。以逗号分隔的 COUNTRY=currency 从环境配置绑定，
 * 启动时拒绝非法/重复规则；只决定币种，不配置金额或改变已有合同。
 */
@Component
@ConfigurationProperties(prefix = "billing.regional-pricing")
public class BillingRegionalPricingProperties {
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    private boolean enabled;
    private String countryCurrencies = "BR=brl";
    private Map<String, String> rules = Map.of("BR", "brl");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCountryCurrencies() { return countryCurrencies; }

    public void setCountryCurrencies(String value) {
        var parsed = new LinkedHashMap<String, String>();
        if (value != null && !value.isBlank()) {
            for (String entry : value.split(",", -1)) {
                String[] pair = entry.split("=", -1);
                if (pair.length != 2) throw invalid();
                String country = pair[0].trim().toUpperCase(Locale.ROOT);
                String currency = pair[1].trim().toUpperCase(Locale.ROOT);
                if (!COUNTRIES.contains(country)) throw invalid();
                try { Currency.getInstance(currency); }
                catch (IllegalArgumentException e) { throw invalid(); }
                if (parsed.putIfAbsent(country, currency.toLowerCase(Locale.ROOT)) != null) throw invalid();
            }
        }
        // 空配置移除全部新购地区规则；不保留 Java/YAML 中旧映射。
        rules = Map.copyOf(parsed);
        countryCurrencies = value == null ? "" : value;
    }

    /** 仅可信国家信号用于匹配；关闭、未匹配或未知国家统一使用既有 USD 策略。 */
    public String newPurchaseCurrency(String country) {
        if (!enabled || country == null) return "usd";
        return rules.getOrDefault(country.trim().toUpperCase(Locale.ROOT), "usd");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("billing.regional-pricing.country-currencies requires unique ISO country=currency pairs");
    }
}
