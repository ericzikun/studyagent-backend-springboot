package com.studyagent.infra.service.billing;

import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.BillingPricingContext;
import com.studyagent.service.domain.billing.BillingQuote;
import com.studyagent.service.domain.billing.BillingPricingException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * 统一选币、只读多币种价格解析及报价对比。调用方先判定商品/账号资格；
 * 订阅动作显式提供实际币种，独立新购才使用可信请求地区。没有数据库写入或汇率换算。
 */
@Service
@RequiredArgsConstructor
public class BillingPricingService {
    private final StripePriceGateway prices;
    private final BillingRegionalPricingProperties configuration;

    public boolean isEnabled() { return configuration.isEnabled(); }
    public String newPurchaseCurrency() {
        return configuration.newPurchaseCurrency(BillingPricingContext.country());
    }
    public String pricingRegion() {
        String country = BillingPricingContext.country();
        return country == null ? "UNKNOWN" : country.trim().toUpperCase(Locale.ROOT);
    }
    public static String subscriptionCurrency(Subscription subscription) {
        if (subscription == null || subscription.getCurrency() == null || subscription.getCurrency().isBlank())
            throw new BillingDomainException("PRICING_CURRENCY_UNAVAILABLE", "Subscription currency is unavailable");
        return subscription.getCurrency().toLowerCase(Locale.ROOT);
    }

    public int amount(String priceId, String currency, Integer baseline, String baselineCurrency,
                      String interval, boolean activeRequired, String productId) {
        // 未启用新购功能的原币种保留旧配置契约；历史非基准币种仍必须读取正确 option。
        if (!isEnabled() && Objects.equals(currency, baselineCurrency == null || baselineCurrency.isBlank() ? "usd" : baselineCurrency.toLowerCase(Locale.ROOT)) && baseline != null) return baseline;
        try {
            Price price = prices.retrieve(priceId, BillingPricingContext.confirmation() != null);
            if (price == null || !Objects.equals(priceId, price.getId())
                    || !Objects.equals(prices.isLive(), Boolean.TRUE.equals(price.getLivemode()))
                    || (productId != null && !productId.isBlank() && !Objects.equals(productId, price.getProduct()))
                    || (activeRequired && !Boolean.TRUE.equals(price.getActive())))
                throw unavailable("Price is unavailable in this environment");
            if (interval != null && !"none".equals(interval)) {
                if (price.getRecurring() == null || !interval.equals(price.getRecurring().getInterval())
                        || !Long.valueOf(1).equals(price.getRecurring().getIntervalCount()))
                    throw unavailable("Price billing interval is invalid");
            } else if (price.getRecurring() != null) throw unavailable("A one-time Price is required");
            return amountForCurrency(price, currency);
        } catch (StripeException e) {
            throw new BillingDomainException("PRICING_CURRENCY_UNAVAILABLE", "Price configuration cannot be verified", e);
        }
    }

    public static int amountForCurrency(Price price, String currency) {
        Long amount = null;
        if (currency != null && currency.equalsIgnoreCase(price.getCurrency())) amount = price.getUnitAmount();
        else if (price.getCurrencyOptions() != null && price.getCurrencyOptions().get(currency) != null)
            amount = price.getCurrencyOptions().get(currency).getUnitAmount();
        if (amount == null || amount < 0 || amount > Integer.MAX_VALUE)
            throw unavailable("The requested currency has no fixed integer price");
        return Math.toIntExact(amount);
    }

    public static BillingDomainException unavailable(String message) {
        return new BillingDomainException("PRICING_CURRENCY_UNAVAILABLE", message);
    }

    public BillingQuote quote(String userId, String kind, String sku, String action, String currency,
                              int amount, String interval, Integer days, Integer renewalAmount,
                              String context, java.time.LocalDateTime effectiveAt) {
        BillingQuote result = BillingQuote.builder().kind(kind).skuCode(sku).action(action).currency(currency)
                .amountCents(amount).billingInterval(interval).trialDays(days).renewalAmountCents(renewalAmount)
                .renewalCurrency(renewalAmount == null ? null : currency).effectiveAt(effectiveAt).build();
        // v2：context 现携带绑区/版本/当前与转正 Price ID（DE/IT 同额 Trial 指纹必须不同）
        result.setQuoteVersion(fingerprint(String.join("|", "v2", userId, kind, sku, action, currency,
                Integer.toString(amount), Objects.toString(interval, ""), Objects.toString(days, ""),
                Objects.toString(renewalAmount, ""), Objects.toString(context, ""), Objects.toString(effectiveAt, ""))));
        return result;
    }

    public void assertConfirmed(BillingQuote quote) {
        var expected = BillingPricingContext.confirmation();
        if (!isEnabled() && expected == null) return;
        if (expected == null || expected.currency() == null || expected.amountCents() == null || expected.quoteVersion() == null)
            throw new BillingPricingException("PRICING_QUOTE_REQUIRED", quote);
        if (!Objects.equals(expected.currency(), quote.getCurrency())
                || !Objects.equals(expected.amountCents(), quote.getAmountCents())
                || !Objects.equals(expected.quoteVersion(), quote.getQuoteVersion()))
            throw new BillingPricingException("PRICING_QUOTE_CHANGED", quote);
    }

    private static String fingerprint(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
