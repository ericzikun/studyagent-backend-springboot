package com.studyagent.service.domain.billing;

/**
 * 同步支付请求的可信地区及报价确认值。HTTP filter 建立/清理作用域，领域层不读取原始头。
 * 后台 webhook 没有请求作用域，必须从实际订阅/订单取币种，不能沿用请求地区。
 */
public final class BillingPricingContext implements AutoCloseable {
    public record Confirmation(String currency, Integer amountCents, String quoteVersion) {}
    private static final ThreadLocal<BillingPricingContext> CURRENT = new ThreadLocal<>();
    private final BillingPricingContext previous;
    private final String country;
    private String userId;
    private Confirmation confirmation;

    private BillingPricingContext(String country) {
        this.previous = CURRENT.get();
        this.country = country;
        CURRENT.set(this);
    }

    public static BillingPricingContext open(String trustedCountry) {
        return new BillingPricingContext(trustedCountry);
    }

    public static String country() { return CURRENT.get() == null ? null : CURRENT.get().country; }
    public static String userId() { return CURRENT.get() == null ? null : CURRENT.get().userId; }
    public static void identify(String userId) { if (CURRENT.get() != null) CURRENT.get().userId = userId; }
    public static Confirmation confirmation() { return CURRENT.get() == null ? null : CURRENT.get().confirmation; }
    public static void confirm(String currency, Integer amountCents, String version) {
        if (CURRENT.get() != null) CURRENT.get().confirmation = currency == null && amountCents == null && version == null ? null : new Confirmation(currency, amountCents, version);
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
