package com.studyagent.infra.client.clerk;

import com.studyagent.infra.metrics.ExternalDependencyMetrics;
import com.studyagent.service.domain.user.ClerkClient;
import com.studyagent.service.domain.user.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ClerkClientImplTest {

    private static final String AUTHORIZED_PARTY = "https://verla.test";

    private ClerkClientImpl clerkClient;
    private KeyPair trustedKeyPair;

    @BeforeEach
    void setUp() throws Exception {
        trustedKeyPair = generateKeyPair();
        clerkClient = new ClerkClientImpl(WebClient.builder().build(), mock(UserRepository.class),
                new ExternalDependencyMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(clerkClient, "clerkSecretKey", "");
        // 覆盖部署平台常见的字面量 \n PEM 配置形式。
        ReflectionTestUtils.setField(clerkClient, "clerkJwtKey", toPem(trustedKeyPair).replace("\n", "\\n"));
        ReflectionTestUtils.setField(clerkClient, "clerkAuthorizedParties", AUTHORIZED_PARTY);
    }

    @Test
    void validSignedTokenShouldReturnIdentityFromVerifiedClaims() {
        String token = createToken(trustedKeyPair, "user_verified", AUTHORIZED_PARTY,
                Instant.now().plusSeconds(300));

        ClerkClient.UserInfo userInfo = clerkClient.verifyToken("Bearer " + token);

        assertThat(userInfo.clerkUserId).isEqualTo("user_verified");
        assertThat(userInfo.email).isEqualTo("verified@example.com");
        assertThat(userInfo.emailVerified).isTrue();
        assertThat(userInfo.displayName).isEqualTo("Verified User");
    }

    @Test
    void tokenSignedByUntrustedKeyShouldBeRejectedEvenWhenClaimsLookValid() throws Exception {
        KeyPair attackerKeyPair = generateKeyPair();
        String forgedToken = createToken(attackerKeyPair, "user_victim", AUTHORIZED_PARTY,
                Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> clerkClient.verifyToken(forgedToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Clerk token");
    }

    @Test
    void tokenFromUnauthorizedPartyShouldBeRejected() {
        String token = createToken(trustedKeyPair, "user_verified", "https://attacker.test",
                Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> clerkClient.verifyToken(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TOKEN_INVALID_AUTHORIZED_PARTIES");
    }

    @Test
    void expiredSignedTokenShouldBeRejected() {
        String token = createToken(trustedKeyPair, "user_verified", AUTHORIZED_PARTY,
                Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> clerkClient.verifyToken(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TOKEN_EXPIRED");
    }

    @Test
    void missingVerificationConfigurationShouldFailClosed() {
        ReflectionTestUtils.setField(clerkClient, "clerkJwtKey", "");
        ReflectionTestUtils.setField(clerkClient, "clerkSecretKey", "sk_test_xxx");

        assertThatThrownBy(() -> clerkClient.verifyToken("header.payload.signature"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    private String createToken(KeyPair keyPair, String subject, String authorizedParty, Instant expiration) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .issuer("https://clerk.verla.test")
                .issuedAt(Date.from(now.minusSeconds(5)))
                .notBefore(Date.from(now.minusSeconds(5)))
                .expiration(Date.from(expiration))
                .claim("azp", authorizedParty)
                .claim("email", "verified@example.com")
                .claim("email_verified", true)
                .claim("name", "Verified User")
                .signWith(keyPair.getPrivate())
                .compact();
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String toPem(KeyPair keyPair) {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
    }
}
