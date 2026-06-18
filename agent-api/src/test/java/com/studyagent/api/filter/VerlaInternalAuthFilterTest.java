package com.studyagent.api.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerlaInternalAuthFilterTest {

    @Test
    void internalPutShouldValidateHmacAgainstRawBinaryBody() throws Exception {
        String secret = "secret";
        byte[] pngLikeBody = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A,
                (byte) 0xFF, 0x00, 0x41
        };
        VerlaInternalAuthFilter filter = filter(secret);

        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT",
                "/v1/internal/verla/v2/uploads/att_1/content");
        request.setRemoteAddr("127.0.0.1");
        request.setContent(pngLikeBody);
        request.addHeader(VerlaInternalAuthFilter.HEADER_TOKEN, secret);
        request.addHeader(VerlaInternalAuthFilter.HEADER_SIG, hmac(secret, pngLikeBody));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    private static VerlaInternalAuthFilter filter(String secret) {
        VerlaInternalAuthFilter filter = new VerlaInternalAuthFilter();
        ReflectionTestUtils.setField(filter, "token", secret);
        ReflectionTestUtils.setField(filter, "hmacSecret", secret);
        ReflectionTestUtils.setField(filter, "allowCidrs", List.of("127.0.0.1/32"));
        ReflectionTestUtils.setField(filter, "skipHmacOnGet", true);
        return filter;
    }

    private static String hmac(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(body);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
