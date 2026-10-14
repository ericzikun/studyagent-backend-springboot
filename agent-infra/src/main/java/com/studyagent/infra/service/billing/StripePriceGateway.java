package com.studyagent.infra.service.billing;

import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.net.RequestOptions;
import com.stripe.param.PriceRetrieveParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** 只读 Price 适配；缓存完整配置，不缓存用户报价。付款确认可强制重新读取。 */
@Component
public class StripePriceGateway {
    private record Cached(Price price, long expiresAt) {}
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    @Value("${stripe.secret-key:}") private String secretKey;

    public Price retrieve(String priceId, boolean fresh) throws StripeException {
        String key = (secretKey != null && secretKey.startsWith("sk_live_") ? "live:" : "test:") + priceId;
        long now = Instant.now().getEpochSecond();
        Cached cached = cache.get(key);
        if (!fresh && cached != null && cached.expiresAt() > now) return cached.price();
        Price price = Price.retrieve(priceId, PriceRetrieveParams.builder().addExpand("currency_options").build(),
                RequestOptions.builder().setApiKey(secretKey).build());
        if (cache.size() >= 1000) cache.clear();
        cache.put(key, new Cached(price, now + 60));
        return price;
    }

    boolean isLive() { return secretKey != null && secretKey.startsWith("sk_live_"); }
}
