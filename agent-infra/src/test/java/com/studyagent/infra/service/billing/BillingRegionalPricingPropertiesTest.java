package com.studyagent.infra.service.billing;

import com.stripe.model.Subscription;
import com.studyagent.service.domain.billing.BillingPricingContext;
import com.studyagent.service.domain.billing.BillingPricingException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class BillingRegionalPricingPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BillingRegionalPricingProperties.class)
    static class Binding {}

    @Test void shippedDefaultsAndEnabledRulesKeepUnconfiguredCountriesInUsd() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var config = context.getBean(BillingRegionalPricingProperties.class);
            assertFalse(config.isEnabled());
            assertEquals("usd", config.newPurchaseCurrency("BR"));
            config.setEnabled(true);
            assertEquals("brl", config.newPurchaseCurrency("BR"));
            for (String country : new String[]{"US", "KR", "SG", "DE"})
                assertEquals("usd", config.newPurchaseCurrency(country));
            assertEquals("usd", config.newPurchaseCurrency(null));
        });
    }

    @Test void replacementMappingSupportsAnotherCountryWithoutChangingTheResolverOrContracts() {
        // 仅此隔离测试配置 KR；实际交付配置依然仅 BR=brl。
        runner.withPropertyValues("billing.regional-pricing.enabled=true",
                "billing.regional-pricing.country-currencies= kr = KRW ").run(context -> {
            assertNull(context.getStartupFailure());
            var config = context.getBean(BillingRegionalPricingProperties.class);
            var pricing = new BillingPricingService(mock(StripePriceGateway.class), config);
            try (var scope = BillingPricingContext.open("KR")) {
                assertEquals("krw", pricing.newPurchaseCurrency());
                assertEquals("KR", pricing.pricingRegion());
                var contract = new Subscription(); contract.setCurrency("brl");
                assertEquals("brl", BillingPricingService.subscriptionCurrency(contract));
                config.setEnabled(false);
                assertEquals("usd", pricing.newPurchaseCurrency());
                assertEquals("brl", BillingPricingService.subscriptionCurrency(contract));
            }
            config.setEnabled(true);
            assertEquals("usd", config.newPurchaseCurrency("BR"));
            assertEquals("UNKNOWN", pricing.pricingRegion());
        });
    }

    @Test void multipleRulesAndEmptyRulesBindAsAnEntireReplacement() {
        runner.withPropertyValues("billing.regional-pricing.enabled=true",
                "billing.regional-pricing.country-currencies=BR=brl,KR=krw").run(context -> {
            assertNull(context.getStartupFailure());
            var config = context.getBean(BillingRegionalPricingProperties.class);
            assertEquals("brl", config.newPurchaseCurrency("BR"));
            assertEquals("krw", config.newPurchaseCurrency("KR"));
            config.setCountryCurrencies("");
            assertEquals("usd", config.newPurchaseCurrency("BR"));
            assertEquals("usd", config.newPurchaseCurrency("KR"));
        });
        runner.withPropertyValues("billing.regional-pricing.enabled=true",
                "billing.regional-pricing.country-currencies=").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("usd", context.getBean(BillingRegionalPricingProperties.class).newPurchaseCurrency("BR"));
        });
    }

    @Test void malformedDuplicateCountryAndCurrencyRulesPreventStartupEvenWhenDisabled() {
        for (String rule : new String[]{"BR", "BR=brl,", "ZZ=brl", "BR=not-money", "BR=", "BR=brl,br=usd", "BR=brl=usd"}) {
            runner.withPropertyValues("billing.regional-pricing.country-currencies=" + rule).run(context -> {
                assertNotNull(context.getStartupFailure(), rule);
                Throwable cause = context.getStartupFailure();
                while (cause.getCause() != null) cause = cause.getCause();
                assertInstanceOf(IllegalArgumentException.class, cause, rule);
            });
        }
    }

    @Test void mappingRemovalInvalidatesPreviouslyConfirmedRegionalQuote() {
        var config = new BillingRegionalPricingProperties(); config.setEnabled(true);
        var prices = mock(StripePriceGateway.class);
        var pricing = new BillingPricingService(prices, config);
        try (var scope = BillingPricingContext.open("BR")) {
            var original = pricing.quote("user", "subscription", "trial", "purchase", pricing.newPurchaseCurrency(),
                    499, "month", 7, 39999, "price_trial:price_monthly", null);
            BillingPricingContext.confirm(original.getCurrency(), original.getAmountCents(), original.getQuoteVersion());
            assertDoesNotThrow(() -> pricing.assertConfirmed(original));
            config.setCountryCurrencies("");
            var current = pricing.quote("user", "subscription", "trial", "purchase", pricing.newPurchaseCurrency(),
                    299, "month", 7, 7999, "price_trial:price_monthly", null);
            assertEquals("usd", current.getCurrency());
            assertEquals("PRICING_QUOTE_CHANGED", assertThrows(BillingPricingException.class,
                    () -> pricing.assertConfirmed(current)).getCode());
            BillingPricingContext.confirm(current.getCurrency(), current.getAmountCents(), current.getQuoteVersion());
            assertDoesNotThrow(() -> pricing.assertConfirmed(current));
            verifyNoInteractions(prices);
        }
    }
}
