package com.studyagent.start.config;

import com.studyagent.infra.service.billing.BillingRegionalPricingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 加载实际 application.yml，验证服务器 env → Spring 绑定，不启动支付/数据库服务。 */
class BillingRegionalPricingConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BillingRegionalPricingProperties.class)
    static class Binding {}

    private ApplicationContextRunner configured(Map<String, Object> values) {
        return new ApplicationContextRunner().withUserConfiguration(Binding.class).withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            sources.addFirst(new SystemEnvironmentPropertySource("pricing-test-env", values));
            try {
                for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")))
                    sources.addLast(source);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    @Test void applicationDefaultsAreDisabledAndOnlyBrazilIsConfigured() {
        configured(Map.of()).run(context -> {
            assertNull(context.getStartupFailure());
            var config = context.getBean(BillingRegionalPricingProperties.class);
            assertFalse(config.isEnabled());
            config.setEnabled(true);
            assertEquals("brl", config.newPurchaseCurrency("BR"));
            assertEquals("usd", config.newPurchaseCurrency("KR"));
            assertEquals("usd", config.newPurchaseCurrency(null));
        });
    }

    @Test void genericEnvironmentVariablesEnableAndReplaceRules() {
        configured(Map.of("BILLING_REGIONAL_PRICING_ENABLED", "true",
                "BILLING_REGIONAL_PRICING_COUNTRY_CURRENCIES", "KR=krw")).run(context -> {
            assertNull(context.getStartupFailure());
            var config = context.getBean(BillingRegionalPricingProperties.class);
            assertTrue(config.isEnabled());
            assertEquals("krw", config.newPurchaseCurrency("KR"));
            assertEquals("usd", config.newPurchaseCurrency("BR"));
        });
    }

    @Test void obsoleteBrazilToggleDoesNotEnableGenericPricing() {
        configured(Map.of("BILLING_BRAZIL_PRICING_ENABLED", "true")).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(BillingRegionalPricingProperties.class).isEnabled());
        });
    }
}
