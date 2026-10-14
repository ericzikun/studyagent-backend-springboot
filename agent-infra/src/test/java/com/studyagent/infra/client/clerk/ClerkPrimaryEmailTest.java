package com.studyagent.infra.client.clerk;

import com.studyagent.service.domain.user.ClerkClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClerkPrimaryEmailTest {
    private Map<String, Object> email(String id, String address, String status) {
        return Map.of("id", id, "email_address", address, "verification", Map.of("status", status));
    }

    @Test void primaryIdSelectsVerifiedBrazilAliasInsteadOfFirstAddress() {
        ClerkClient.UserInfo info = ReflectionTestUtils.invokeMethod(new ClerkClientImpl(org.mockito.Mockito.mock(org.springframework.web.reactive.function.client.WebClient.class), org.mockito.Mockito.mock(com.studyagent.service.domain.user.UserRepository.class)), "extractUserInfo", Map.of(
            "id", "user_br", "primary_email_address_id", "email_primary", "email_addresses", List.of(
                email("email_old", "old@example.com", "verified"), email("email_primary", "test+location_BR@example.com", "verified"))));
        assertNotNull(info); assertEquals("test+location_BR@example.com", info.email); assertTrue(info.emailVerified);
    }

    @Test void missingPrimaryOrUnverifiedPrimaryCannotBecomeVerifiedEmail() {
        ClerkClient.UserInfo missing = ReflectionTestUtils.invokeMethod(new ClerkClientImpl(org.mockito.Mockito.mock(org.springframework.web.reactive.function.client.WebClient.class), org.mockito.Mockito.mock(com.studyagent.service.domain.user.UserRepository.class)), "extractUserInfo", Map.of(
            "id", "user_br", "email_addresses", List.of(email("first", "first@example.com", "verified"))));
        assertNotNull(missing); assertNull(missing.email);
        ClerkClient.UserInfo unverified = ReflectionTestUtils.invokeMethod(new ClerkClientImpl(org.mockito.Mockito.mock(org.springframework.web.reactive.function.client.WebClient.class), org.mockito.Mockito.mock(com.studyagent.service.domain.user.UserRepository.class)), "extractUserInfo", Map.of(
            "id", "user_br", "primary_email_address_id", "primary", "email_addresses", List.of(email("primary", "unverified@example.com", "unverified"))));
        assertNotNull(unverified); assertFalse(unverified.emailVerified);
    }
}
