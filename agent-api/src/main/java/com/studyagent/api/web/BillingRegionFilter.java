package com.studyagent.api.web;

import com.studyagent.service.domain.billing.BillingPricingContext;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** 自有代理来源凭证校验；公网国家头、私网对端和客户端参数都不是资格证明。 */
@Component
@RequiredArgsConstructor
public class BillingRegionFilter extends OncePerRequestFilter {
    private final Environment environment;
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    @Value("${billing.regional-pricing.test-override:}") private String testOverride;
    @Value("${billing.regional-pricing.proxy-secret:}") private String proxySecret;

    @PostConstruct
    public void validateConfiguration() {
        if (testOverride == null || testOverride.isBlank()) return;
        var profiles = Arrays.asList(environment.getActiveProfiles());
        if (profiles.isEmpty() || profiles.stream().anyMatch(p -> !Set.of("local", "test").contains(p)))
            throw new IllegalStateException("BILLING_REGION_TEST_OVERRIDE is allowed only in local/test profiles");
        testOverride = "unknown".equalsIgnoreCase(testOverride.trim()) ? "unknown" : testOverride.trim().toUpperCase(Locale.ROOT);
        if (!"unknown".equals(testOverride) && !COUNTRIES.contains(testOverride))
            throw new IllegalStateException("BILLING_REGION_TEST_OVERRIDE must be an ISO country or unknown");
    }

    String trustedCountry(HttpServletRequest request) {
        if (testOverride != null && !testOverride.isBlank()) return "unknown".equals(testOverride) ? null : testOverride;
        String supplied = request.getHeader("X-Verla-Pricing-Proxy-Secret");
        if (proxySecret == null || proxySecret.length() < 32 || supplied == null
                || !MessageDigest.isEqual(proxySecret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            return null;
        String country = request.getHeader("X-Verla-Pricing-Country");
        if (country == null) return null;
        country = country.trim().toUpperCase(Locale.ROOT);
        return COUNTRIES.contains(country) ? country : null;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (var scope = BillingPricingContext.open(trustedCountry(request))) {
            if (request.getRequestURI().startsWith("/v1/billing/") || request.getRequestURI().equals("/v1/payment/config"))
                response.setHeader("Cache-Control", "private, no-store");
            chain.doFilter(request, response);
        }
    }
}
