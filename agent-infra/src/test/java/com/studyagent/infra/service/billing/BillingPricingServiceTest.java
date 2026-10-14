package com.studyagent.infra.service.billing;

import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.studyagent.service.domain.billing.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No exchange-rate fallback, client-price authority or implicit contract currency. */
class BillingPricingServiceTest {
    StripePriceGateway gateway = mock(StripePriceGateway.class);
    BillingRegionalPricingProperties configuration = new BillingRegionalPricingProperties();
    BillingPricingService pricing = new BillingPricingService(gateway, configuration);

    private void enable() { configuration.setEnabled(true); }
    private Price price(String interval) {
        Price price = new Price(); price.setId("price_trial"); price.setCurrency("usd"); price.setUnitAmount(299L);
        price.setActive(true); price.setLivemode(false); price.setProduct("prod_trial");
        Price.CurrencyOption brl = new Price.CurrencyOption(); brl.setUnitAmount(499L);
        Price.CurrencyOption krw = new Price.CurrencyOption(); krw.setUnitAmount(4100L);
        price.setCurrencyOptions(Map.of("brl", brl, "krw", krw));
        if (interval != null) { Price.Recurring recurring = new Price.Recurring(); recurring.setInterval(interval); recurring.setIntervalCount(1L); price.setRecurring(recurring); }
        return price;
    }

    @Test void brazilNewPurchaseAndTravelingContractHaveDifferentAuthorities() {
        enable();
        try (var ignored = BillingPricingContext.open("BR")) {
            assertEquals("brl", pricing.newPurchaseCurrency());
            Subscription contract = new Subscription(); contract.setCurrency("krw");
            assertEquals("krw", BillingPricingService.subscriptionCurrency(contract));
        }
        assertNull(BillingPricingContext.country()); assertEquals("usd", pricing.newPurchaseCurrency());
        try (var ignored = BillingPricingContext.open("SG")) { assertEquals("usd", pricing.newPurchaseCurrency()); }
    }

    @Test void fixedOptionsAreUsedWithoutConversionOrUsdFallback() throws Exception {
        enable(); when(gateway.retrieve("price_trial", false)).thenReturn(price("week"));
        assertEquals(499, pricing.amount("price_trial", "brl", 299, "usd", "week", true, "prod_trial"));
        assertEquals(4100, pricing.amount("price_trial", "krw", 299, "usd", "week", false, "prod_trial"));
        assertEquals("PRICING_CURRENCY_UNAVAILABLE", assertThrows(BillingDomainException.class,
            () -> pricing.amount("price_trial", "eur", 299, "usd", "week", true, "prod_trial")).getCode());
    }

    @Test void wrongEnvironmentProductIntervalAndRetiredSalesFailClosed() throws Exception {
        enable(); Price price = price("week"); when(gateway.retrieve("price_trial", false)).thenReturn(price);
        assertThrows(BillingDomainException.class, () -> pricing.amount("price_trial", "brl", 299, "usd", "month", true, "prod_trial"));
        assertThrows(BillingDomainException.class, () -> pricing.amount("price_trial", "brl", 299, "usd", "week", true, "prod_other"));
        price.setLivemode(true);
        assertThrows(BillingDomainException.class, () -> pricing.amount("price_trial", "brl", 299, "usd", "week", true, "prod_trial"));
        price.setLivemode(false); price.setActive(false);
        assertThrows(BillingDomainException.class, () -> pricing.amount("price_trial", "brl", 299, "usd", "week", true, "prod_trial"));
        assertEquals(499, pricing.amount("price_trial", "brl", 299, "usd", "week", false, "prod_trial"));
    }

    @Test void confirmationBindsOwnerSkuChargeAndRenewalAndBypassesPriceCache() throws Exception {
        enable();
        try (var ignored = BillingPricingContext.open("BR")) {
            BillingQuote quote = pricing.quote("user_A", "subscription", "trial", "purchase", "brl", 499, "month", 7, 39999, "price_trial:price_monthly", null);
            assertEquals("PRICING_QUOTE_REQUIRED", assertThrows(BillingPricingException.class, () -> pricing.assertConfirmed(quote)).getCode());
            BillingPricingContext.confirm("brl", 499, quote.getQuoteVersion()); pricing.assertConfirmed(quote);
            var otherUser = pricing.quote("user_B", "subscription", "trial", "purchase", "brl", 499, "month", 7, 39999, "price_trial:price_monthly", null);
            assertEquals("PRICING_QUOTE_CHANGED", assertThrows(BillingPricingException.class, () -> pricing.assertConfirmed(otherUser)).getCode());
            var changedRenewal = pricing.quote("user_A", "subscription", "trial", "purchase", "brl", 499, "month", 7, 40000, "price_trial:price_monthly", null);
            assertThrows(BillingPricingException.class, () -> pricing.assertConfirmed(changedRenewal));
            when(gateway.retrieve("price_trial", true)).thenReturn(price("week"));
            assertEquals(499, pricing.amount("price_trial", "brl", 299, "usd", "week", true, "prod_trial"));
            verify(gateway).retrieve("price_trial", true);
        }
    }

    @Test void rollbackDuringAnOpenConfirmationCannotSilentlyChangeThePrice() {
        try (var ignored = BillingPricingContext.open("BR")) {
            var brl = pricing.quote("user", "subscription", "trial", "purchase", "brl", 499, "month", 7, 39999, "price", null);
            BillingPricingContext.confirm("brl", 499, brl.getQuoteVersion());
            var usd = pricing.quote("user", "subscription", "trial", "purchase", "usd", 299, "month", 7, 7999, "price", null);
            assertEquals("PRICING_QUOTE_CHANGED", assertThrows(BillingPricingException.class, () -> pricing.assertConfirmed(usd)).getCode());
            BillingPricingContext.confirm(null, null, null); assertDoesNotThrow(() -> pricing.assertConfirmed(usd));
        }
    }

    @Test void rollbackKeepsBrlContractsAndLegacyUsdClientsWorking() throws Exception {
        assertEquals(299, pricing.amount("price_trial", "usd", 299, null, "week", true, null));
        verifyNoInteractions(gateway);
        when(gateway.retrieve("price_trial", false)).thenReturn(price("week"));
        assertEquals(499, pricing.amount("price_trial", "brl", 299, "usd", "week", false, "prod_trial"));
        assertThrows(BillingDomainException.class, () -> BillingPricingService.subscriptionCurrency(new Subscription()));
    }
}
