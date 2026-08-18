package com.studyagent.api.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    void prefersProxyOverwrittenRealIpOverSpoofableForwardedChain() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("172.20.0.4");
        when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.4");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.99, 198.51.100.4");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.4");
    }

    @Test
    void fallsBackToRemoteAddress() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void ignoresForwardedHeadersFromDirectPublicClients() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("198.51.100.80");
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.99");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.80");
    }
}
