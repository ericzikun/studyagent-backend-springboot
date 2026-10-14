package com.studyagent.api.web;

import com.studyagent.service.domain.billing.BillingPricingContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class BillingRegionFilterTest {
    private static final String SECRET = "local-test-secret-with-at-least-32-characters";
    private BillingRegionFilter filter(String... profiles) {
        MockEnvironment environment = new MockEnvironment(); environment.setActiveProfiles(profiles);
        var filter = new BillingRegionFilter(environment);
        ReflectionTestUtils.setField(filter, "proxySecret", SECRET); return filter;
    }

    @Test void publicAndPrivatePeerHeadersCannotAuthorizeBrazil() {
        var filter = filter("prod"); var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1"); request.addHeader("CF-IPCountry", "BR");
        request.addHeader("X-Verla-Pricing-Country", "BR");
        assertNull(filter.trustedCountry(request));
        request.addHeader("X-Verla-Pricing-Proxy-Secret", "wrong"); assertNull(filter.trustedCountry(request));
    }

    @Test void ownProxyEvidenceIsNormalizedAndInvalidCountryBecomesUnknown() {
        var filter = filter("prod"); var request = new MockHttpServletRequest();
        request.addHeader("X-Verla-Pricing-Proxy-Secret", SECRET); request.addHeader("X-Verla-Pricing-Country", "br");
        assertEquals("BR", filter.trustedCountry(request));
        request.removeHeader("X-Verla-Pricing-Country"); request.addHeader("X-Verla-Pricing-Country", "XX");
        assertNull(filter.trustedCountry(request));
    }

    @Test void productionMixedOrMissingProfilesRejectTestOverride() {
        for (var profiles : new String[][] { {}, {"prod"}, {"local", "prod"} }) {
            var filter = filter(profiles); ReflectionTestUtils.setField(filter, "testOverride", "BR");
            assertThrows(IllegalStateException.class, filter::validateConfiguration);
        }
        var local = filter("local"); ReflectionTestUtils.setField(local, "testOverride", "BR");
        assertDoesNotThrow(local::validateConfiguration); assertEquals("BR", local.trustedCountry(new MockHttpServletRequest()));
    }

    @Test void requestScopeAndNoStoreSurviveExceptionalExit() {
        var filter = filter("test"); ReflectionTestUtils.setField(filter, "testOverride", "BR");
        var request = new MockHttpServletRequest("GET", "/v1/billing/config"); var response = new MockHttpServletResponse();
        assertThrows(jakarta.servlet.ServletException.class, () -> filter.doFilter(request, response, (req, res) -> {
            assertEquals("BR", BillingPricingContext.country()); BillingPricingContext.identify("user_A");
            throw new jakarta.servlet.ServletException("test");
        }));
        assertNull(BillingPricingContext.country()); assertNull(BillingPricingContext.userId());
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
    }

    @Test void localOverrideSupportsAnyValidCountryButNeverGrantsProductionSimulation() {
        for (String country : new String[]{"KR", "br", "SG"}) {
            var local = filter("local"); ReflectionTestUtils.setField(local, "testOverride", country);
            assertDoesNotThrow(local::validateConfiguration);
            assertEquals(country.toUpperCase(java.util.Locale.ROOT), local.trustedCountry(new MockHttpServletRequest()));
            var production = filter("prod"); ReflectionTestUtils.setField(production, "testOverride", country);
            assertThrows(IllegalStateException.class, production::validateConfiguration);
        }
        var unknown = filter("test"); ReflectionTestUtils.setField(unknown, "testOverride", "unknown");
        assertDoesNotThrow(unknown::validateConfiguration); assertNull(unknown.trustedCountry(new MockHttpServletRequest()));
        for (String invalid : new String[]{"XX", "ZZ", "USD", "BR=brl"}) {
            var local = filter("local"); ReflectionTestUtils.setField(local, "testOverride", invalid);
            assertThrows(IllegalStateException.class, local::validateConfiguration);
        }
    }
}
