package com.studyagent.api.interceptor;

import com.studyagent.service.domain.user.ClerkClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthInterceptorTest {

    @Test
    void internalVerlaUploadShouldBypassBearerAuth() {
        ClerkClient clerkClient = mock(ClerkClient.class);
        AuthInterceptor interceptor = new AuthInterceptor(clerkClient);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT",
                "/v1/internal/verla/v2/uploads/att_1/content");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(clerkClient);
    }

    @Test
    void sseQueryTokenShouldUseSharedSignatureVerification() {
        ClerkClient clerkClient = mock(ClerkClient.class);
        when(clerkClient.verifyToken("signed-sse-token")).thenReturn(userInfo("user_sse"));
        AuthInterceptor interceptor = new AuthInterceptor(clerkClient);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/verla/conversations/vc_123/events");
        request.addParameter("access_token", "signed-sse-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(request.getAttribute("clerkUserId")).isEqualTo("user_sse");
        verify(clerkClient).verifyToken("signed-sse-token");
    }

    @Test
    void authorizationHeaderShouldUseSharedSignatureVerification() {
        ClerkClient clerkClient = mock(ClerkClient.class);
        when(clerkClient.verifyToken("signed-header-token")).thenReturn(userInfo("user_header"));
        AuthInterceptor interceptor = new AuthInterceptor(clerkClient);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/auth/me");
        request.addHeader("Authorization", "Bearer signed-header-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(request.getAttribute("clerkUserId")).isEqualTo("user_header");
        verify(clerkClient).verifyToken("signed-header-token");
    }

    @Test
    void forgedSseTokenShouldReturnUnauthorizedWithoutFallback() throws Exception {
        ClerkClient clerkClient = mock(ClerkClient.class);
        when(clerkClient.verifyToken("forged-token"))
                .thenThrow(new IllegalArgumentException("Invalid Clerk token: TOKEN_INVALID_SIGNATURE"));
        AuthInterceptor interceptor = new AuthInterceptor(clerkClient);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/verla/conversations/vc_123/events");
        request.addParameter("access_token", "forged-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_SIGNATURE_INVALID");
        verify(clerkClient).verifyToken("forged-token");
    }

    @Test
    void verificationInfrastructureFailureShouldReturnServiceUnavailable() throws Exception {
        ClerkClient clerkClient = mock(ClerkClient.class);
        when(clerkClient.verifyToken("signed-token"))
                .thenThrow(new IllegalStateException("Clerk token verification unavailable"));
        AuthInterceptor interceptor = new AuthInterceptor(clerkClient);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/auth/me");
        request.addHeader("Authorization", "Bearer signed-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("AUTH_SERVICE_UNAVAILABLE");
        verify(clerkClient).verifyToken("signed-token");
    }

    @Test
    void queryTokenOutsideVerlaSsePathShouldNotBeAccepted() {
        ClerkClient clerkClient = mock(ClerkClient.class);
        AuthInterceptor interceptor = new AuthInterceptor(clerkClient);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/auth/me");
        request.addParameter("access_token", "query-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        verify(clerkClient, never()).verifyToken("query-token");
    }

    private ClerkClient.UserInfo userInfo(String clerkUserId) {
        ClerkClient.UserInfo userInfo = new ClerkClient.UserInfo();
        userInfo.clerkUserId = clerkUserId;
        return userInfo;
    }
}
