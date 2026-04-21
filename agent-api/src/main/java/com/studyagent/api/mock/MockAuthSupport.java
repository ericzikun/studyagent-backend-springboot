package com.studyagent.api.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Mock 模式认证工具：从 Bearer JWT 中尽力解析用户信息。
 */
@Component
public class MockAuthSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public record MockUser(
        String uid,
        String email,
        String displayName,
        String avatarUrl,
        String locale,
        Boolean isAdmin,
        Boolean emailVerified,
        String createdAt,
        String lastLoginAt
    ) {}

    public MockUser requireUser(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            return null;
        }
        return parseUserFromToken(token);
    }

    public MockUser parseUserFromToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return fallbackUser();
            }
            String payload = decodeBase64Url(parts[1]);
            JsonNode root = OBJECT_MAPPER.readTree(payload);

            String uid = firstNonEmpty(
                text(root, "sub"),
                text(root, "user_id"),
                "user_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
            );
            String email = firstNonEmpty(text(root, "email"), text(root, "email_address"), uid + "@studyagent.ai");
            String displayName = firstNonEmpty(text(root, "name"), text(root, "first_name"), "Mock User");
            String avatar = firstNonEmpty(text(root, "image_url"), text(root, "picture"), null);
            String locale = firstNonEmpty(text(root, "locale"), "en");

            String now = LocalDateTime.now().toString();
            return new MockUser(uid, email, displayName, avatar, locale, false, true, now, now);
        } catch (Exception ignored) {
            return fallbackUser();
        }
    }

    private MockUser fallbackUser() {
        String uid = "user_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String now = LocalDateTime.now().toString();
        return new MockUser(uid, uid + "@studyagent.ai", "Mock User", null, "en", false, true, now, now);
    }

    private String decodeBase64Url(String raw) {
        String padded = raw;
        int mod = raw.length() % 4;
        if (mod > 0) {
            padded = raw + "=".repeat(4 - mod);
        }
        byte[] decoded = Base64.getUrlDecoder().decode(padded);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private String text(JsonNode root, String field) {
        if (root == null || root.isMissingNode() || field == null) {
            return null;
        }
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
