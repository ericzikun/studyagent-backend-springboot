package com.studyagent.infra.service.billing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.net.RequestOptions;
import com.stripe.param.PriceRetrieveParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.CompletionException;

/** 只读 Price 适配；缓存完整配置，不缓存用户报价。付款确认可强制重新读取。 */
@Component
public class StripePriceGateway {
    private static final long TTL_NANOS = Duration.ofMinutes(10).toNanos();
    private static final long REFRESH_AFTER_NANOS = Duration.ofMinutes(8).toNanos();

    private record Cached(Price price, long loadedAt) {}

    private final Cache<String, Cached> cache = Caffeine.newBuilder().maximumSize(1000).build();
    private final Ticker ticker;
    @Value("${stripe.secret-key:}") private String secretKey;

    public StripePriceGateway() {
        this(Ticker.systemTicker());
    }

    StripePriceGateway(Ticker ticker) {
        this.ticker = ticker;
    }

    public Price retrieve(String priceId, boolean fresh) throws StripeException {
        if (priceId == null) {
            return fetchPrice(null, false);
        }
        return lookup(priceId, fresh, false, TTL_NANOS);
    }

    void refreshIfDue(String priceId) throws StripeException {
        lookup(priceId, false, true, REFRESH_AFTER_NANOS);
    }

    private Price lookup(String priceId, boolean fresh, boolean background, long maxAge) throws StripeException {
        Cached cached = cache.getIfPresent(priceId);
        if (!fresh && isUsable(cached, maxAge)) {
            return cached.price();
        }
        try {
            // 同一 Price 的冷读、预热和强制取价串行更新，避免重复回源或旧结果覆盖新结果。
            return cache.asMap().compute(priceId, (key, previous) -> {
                if (!fresh && isUsable(previous, maxAge)) {
                    return previous;
                }
                try {
                    Price price = fetchPrice(key, background);
                    return new Cached(price, ticker.read());
                } catch (StripeException e) {
                    // compute 失败时保留原条目及其有效期。
                    throw new CompletionException(e);
                }
            }).price();
        } catch (CompletionException e) {
            if (e.getCause() instanceof StripeException stripeException) {
                throw stripeException;
            }
            throw e;
        }
    }

    private boolean isUsable(Cached cached, long maxAge) {
        return cached != null && ticker.read() - cached.loadedAt() < maxAge;
    }

    Price fetchPrice(String priceId, boolean background) throws StripeException {
        RequestOptions.RequestOptionsBuilder options = RequestOptions.builder().setApiKey(secretKey);
        if (background) {
            options.setConnectTimeout(2_000).setReadTimeout(3_000).setMaxNetworkRetries(0);
        }
        return Price.retrieve(priceId, PriceRetrieveParams.builder().addExpand("currency_options").build(),
                options.build());
    }

    boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank() && !"sk_test_xxx".equals(secretKey);
    }

    boolean isLive() { return secretKey != null && secretKey.startsWith("sk_live_"); }
}
