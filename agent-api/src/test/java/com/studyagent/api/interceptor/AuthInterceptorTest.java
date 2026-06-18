package com.studyagent.api.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AuthInterceptorTest {

    @Test
    void internalVerlaUploadShouldBypassBearerAuth() {
        AuthInterceptor interceptor = new AuthInterceptor(null);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT",
                "/v1/internal/verla/v2/uploads/att_1/content");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
