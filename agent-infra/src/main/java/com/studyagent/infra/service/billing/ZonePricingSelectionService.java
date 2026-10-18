package com.studyagent.infra.service.billing;

import com.studyagent.infra.entity.PricingCountryRouteEntity;
import com.studyagent.infra.entity.PricingZoneEntity;
import com.studyagent.infra.entity.PricingZonePriceEntity;
import com.studyagent.infra.entity.UserSubscriptionEntity;
import com.studyagent.service.domain.billing.BillingPricingContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 统一选价 {@code PricingSelection} 的唯一构建点（plan-a-db §5 四路径）。
 *
 * <p>①独立新购：三级门控（env 开关 → 区 is_active → 国家 is_enabled），任一前置不满足返回
 * empty 走 legacy；国家已启用后的任何缺口（区未启用/绑定缺/作废/price_id 未回填/金额缺/
 * Stripe 实读不一致）一律 {@code PRICING_CURRENCY_UNAVAILABLE}，禁止回退 USD。
 * ②会员合同动作：合同三列（pricing_zone/price_version/currency）解析，不看任何开关，
 * legacy 合同（zone NULL）返回 empty 走 legacy。③Study Pass 已有合同按自身 Price 反查。
 * ④credit packs 不进本链路（PaymentController legacy）。</p>
 *
 * <p>金额守恒：注册表 currency_amounts 为基准，Stripe 实读金额必须逐字相等才可售
 * （复用 {@link BillingPricingService#amount} 的 livemode/product/周期/active 校验）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZonePricingSelectionService {

    public static final String KIND_SUBSCRIPTION_PLAN = "subscription_plan";
    public static final String KIND_ADDON = "addon";
    public static final String KIND_STUDY_PASS = "study_pass";

    /** 内部值对象，不暴露前端；zone 为空表示 legacy 选价。 */
    public record PricingSelection(
            String zoneCode,
            String priceVersion,
            String skuCode,
            String skuKind,
            String priceType,
            String billingInterval,
            Integer intervalCount,
            String stripePriceId,
            String stripeProductId,
            String currency,
            int amountMinor,
            String renewalSkuCode,
            String renewalPriceId,
            Integer renewalAmountMinor) {
    }

    /** 元数据/反查用的轻量注册表信息，不做 Stripe 校验。 */
    public record RegistryBinding(
            String zoneCode,
            String priceVersion,
            String skuCode,
            String skuKind,
            String renewalSkuCode,
            String renewalPriceId) {
    }

    private final ZonePricingCatalog catalog;
    private final BillingPricingService pricingService;

    /** 区外国家/无可信国家头的默认档（1.00 倍率）；仅在 env 开关开启时生效。 */
    @org.springframework.beans.factory.annotation.Value(
            "${billing.regional-pricing.zone-pricing.default-zone:Z100}")
    private String defaultZoneCode;

    /**
     * 路径①：独立新购（未登录/无有效合同）。可信国家来自请求作用域。
     * 国家未路由（区外/无头）→ 默认 1.00 档（USD），保证新购永远不走 legacy $79.99；
     * 仅当默认区自身未启用时返回 empty（回滚逃生门）。
     */
    public Optional<PricingSelection> newPurchaseSelection(String skuCode, String expectedKind) {
        if (!pricingService.isEnabled()) {
            return Optional.empty();
        }
        String country = BillingPricingContext.country();
        Optional<PricingCountryRouteEntity> routeOpt = catalog.enabledRoute(country);
        String zoneCode;
        String currency;
        if (routeOpt.isPresent()) {
            zoneCode = routeOpt.get().getZoneCode();
            currency = normalizeCurrency(routeOpt.get().getCurrency());
        } else {
            String fallbackZone = defaultZoneCode == null || defaultZoneCode.isBlank()
                    ? "Z100" : defaultZoneCode.trim();
            PricingZoneEntity zone = catalog.zone(fallbackZone).orElse(null);
            if (zone == null || !Boolean.TRUE.equals(zone.getIsActive())) {
                return Optional.empty();
            }
            zoneCode = fallbackZone;
            currency = "usd";
        }
        // 国家已启用 / 默认档已启用 = 已承诺按区价卖：此后任何缺口 fail-closed，不回退 USD。
        PricingZoneEntity zone = catalog.zone(zoneCode).orElse(null);
        if (zone == null || !Boolean.TRUE.equals(zone.getIsActive())) {
            log.warn("zone pricing: country {} routed to inactive zone {}", country, zoneCode);
            throw BillingPricingService.unavailable("Pricing zone is not active for this country");
        }
        PricingZonePriceEntity binding = catalog.activeBinding(zone.getZoneCode(), skuCode)
                .orElseThrow(() -> BillingPricingService.unavailable(
                        "No active zone price binding for " + skuCode));
        requireKind(binding, expectedKind);
        return Optional.of(build(binding, currency));
    }

    /**
     * 路径②：会员合同动作。合同三列解析；zone 为 NULL 的 legacy 合同返回 empty。
     * 币种=合同币种列（展示价=账单价），金额取合同版本（不看区当前销售版本）。
     */
    public Optional<PricingSelection> memberSelection(
            UserSubscriptionEntity contract, String skuCode, String expectedKind) {
        if (contract == null || isBlank(contract.getPricingZone())) {
            return Optional.empty();
        }
        if (isBlank(contract.getPriceVersion()) || isBlank(contract.getCurrency())) {
            throw BillingPricingService.unavailable("Zone contract is missing version or currency");
        }
        PricingZonePriceEntity binding = catalog.contractBinding(
                        contract.getPricingZone().trim(),
                        contract.getPriceVersion().trim(),
                        skuCode)
                .orElseThrow(() -> BillingPricingService.unavailable(
                        "No zone price binding for contract target " + skuCode));
        requireKind(binding, expectedKind);
        return Optional.of(build(binding, normalizeCurrency(contract.getCurrency())));
    }

    /**
     * 反查解析：实际 Stripe Price ID（webhook / 转正 Schedule / Study Pass 同步）。
     * 不在任何开关与 is_active 之后；legacy Price 返回 empty。
     */
    public Optional<PricingSelection> selectionByPriceId(String stripePriceId, String currency) {
        if (isBlank(stripePriceId) || isBlank(currency)) {
            return Optional.empty();
        }
        return catalog.bindingById(stripePriceId.trim())
                .map(binding -> build(binding, normalizeCurrency(currency)));
    }

    /**
     * 路径③-合同反查（webhook 同步专用）：注册表-only，不做 Stripe 实读。
     * 已售合同的解析不因 Stripe 端价格配置演进而失败；金额基准=注册表
     * currency_amounts[合同币种]，币种缺失=注册表损坏，fail-closed 让事件重试告警。
     */
    public Optional<PricingSelection> contractSelectionByPriceId(String stripePriceId, String currency) {
        if (isBlank(stripePriceId)) {
            return Optional.empty();
        }
        PricingZonePriceEntity binding = catalog.bindingById(stripePriceId.trim()).orElse(null);
        if (binding == null) {
            return Optional.empty();
        }
        String contractCurrency = normalizeCurrency(currency);
        if (contractCurrency.isEmpty()) {
            throw BillingPricingService.unavailable(
                    "Zone contract currency is missing: " + binding.getSkuCode());
        }
        return Optional.of(buildFromRegistry(binding, contractCurrency));
    }

    /**
     * Price ID → SKU 身份核对（区绑定或 legacy 别名），用于漂移检测；
     * 不校验金额、不看开关。
     */
    public Optional<String> skuCodeForPriceId(String stripePriceId) {
        if (isBlank(stripePriceId)) {
            return Optional.empty();
        }
        String id = stripePriceId.trim();
        Optional<PricingZonePriceEntity> binding = catalog.bindingById(id);
        if (binding.isPresent()) {
            return binding.map(PricingZonePriceEntity::getSkuCode);
        }
        return catalog.alias(id).map(com.studyagent.infra.entity.StripePriceAliasEntity::getSkuCode);
    }

    /** 元数据/指纹/订单快照用：Price ID → 区/版本/SKU/转正价。legacy 返回 empty，不校验金额。 */
    public Optional<RegistryBinding> registryBinding(String stripePriceId) {
        if (isBlank(stripePriceId)) {
            return Optional.empty();
        }
        return catalog.bindingById(stripePriceId.trim()).map(binding -> new RegistryBinding(
                binding.getZoneCode(),
                binding.getPriceVersion(),
                binding.getSkuCode(),
                binding.getSkuKind(),
                binding.getRenewalSkuCode(),
                renewalPriceId(binding)));
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private PricingSelection build(PricingZonePriceEntity binding, String currency) {
        int amount = validatedAmount(binding, currency);
        String renewalPriceId = null;
        Integer renewalAmount = null;
        String renewalSkuCode = binding.getRenewalSkuCode();
        if (renewalSkuCode != null && !renewalSkuCode.isBlank()) {
            // 试用转正：同区同版本 renewal 绑定，进指纹与 Schedule 第二段。
            PricingZonePriceEntity renewal = catalog.contractBinding(
                            binding.getZoneCode(), binding.getPriceVersion(), renewalSkuCode)
                    .orElseThrow(() -> BillingPricingService.unavailable(
                            "Renewal binding missing for " + binding.getSkuCode()));
            renewalPriceId = renewal.getStripePriceId();
            if (isBlank(renewalPriceId)) {
                throw BillingPricingService.unavailable("Renewal price id not backfilled");
            }
            renewalAmount = validatedAmount(renewal, currency);
        }
        return new PricingSelection(
                binding.getZoneCode(),
                binding.getPriceVersion(),
                binding.getSkuCode(),
                binding.getSkuKind(),
                binding.getPriceType(),
                binding.getBillingInterval(),
                binding.getIntervalCount(),
                binding.getStripePriceId(),
                binding.getStripeProductId(),
                currency,
                amount,
                renewalSkuCode,
                renewalPriceId,
                renewalAmount);
    }

    /** 注册表-only 构建：合同反查用，金额不做 Stripe 实读校验；转正链不解析。 */
    private PricingSelection buildFromRegistry(PricingZonePriceEntity binding, String currency) {
        Map<String, Integer> amounts = catalog.currencyAmounts(binding).orElse(Map.of());
        Integer amount = amounts.get(currency);
        if (amount == null) {
            throw BillingPricingService.unavailable(
                    "Zone contract has no registry amount for " + binding.getSkuCode() + " in " + currency);
        }
        return new PricingSelection(
                binding.getZoneCode(),
                binding.getPriceVersion(),
                binding.getSkuCode(),
                binding.getSkuKind(),
                binding.getPriceType(),
                binding.getBillingInterval(),
                binding.getIntervalCount(),
                binding.getStripePriceId(),
                binding.getStripeProductId(),
                currency,
                amount,
                null,
                null,
                null);
    }

    /** 注册表金额为基准，Stripe 实读必须逐字相等；任何不一致=不可售。 */
    private int validatedAmount(PricingZonePriceEntity binding, String currency) {
        if (isBlank(binding.getStripePriceId())) {
            throw BillingPricingService.unavailable("Zone price id not backfilled: " + binding.getSkuCode());
        }
        Map<String, Integer> amounts = catalog.currencyAmounts(binding).orElse(Map.of());
        Integer registryAmount = amounts.get(currency);
        if (registryAmount == null) {
            throw BillingPricingService.unavailable(
                    "No registry amount for " + binding.getSkuCode() + " in " + currency);
        }
        String interval = "one_time".equals(binding.getPriceType())
                ? null
                : binding.getBillingInterval();
        int stripeAmount = pricingService.amount(
                binding.getStripePriceId(), currency, null, null,
                interval, true, binding.getStripeProductId());
        if (stripeAmount != registryAmount) {
            log.warn("zone pricing amount divergence: {} {} stripe={} registry={}",
                    binding.getSkuCode(), currency, stripeAmount, registryAmount);
            throw BillingPricingService.unavailable("Stripe amount diverges from registry");
        }
        return registryAmount;
    }

    private String renewalPriceId(PricingZonePriceEntity binding) {
        if (isBlank(binding.getRenewalSkuCode())) {
            return null;
        }
        return catalog.contractBinding(binding.getZoneCode(), binding.getPriceVersion(), binding.getRenewalSkuCode())
                .map(PricingZonePriceEntity::getStripePriceId)
                .filter(id -> !isBlank(id))
                .orElse(null);
    }

    private static void requireKind(PricingZonePriceEntity binding, String expectedKind) {
        if (expectedKind != null && !Objects.equals(expectedKind, binding.getSkuKind())) {
            throw BillingPricingService.unavailable(
                    "Zone binding kind mismatch: " + binding.getSkuCode()
                            + " is " + binding.getSkuKind() + ", expected " + expectedKind);
        }
    }

    private static String normalizeCurrency(String currency) {
        return currency == null ? "" : currency.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
