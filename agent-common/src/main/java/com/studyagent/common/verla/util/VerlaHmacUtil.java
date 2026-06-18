package com.studyagent.common.verla.util;

import lombok.experimental.UtilityClass;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

/**
 * HMAC-SHA256 工具：用于 /internal 鉴权 + 双向签名校验
 * <p>
 * 对应文档 §23.1 L2
 */
@UtilityClass
public class VerlaHmacUtil {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * 计算 HMAC-SHA256 hex
     */
    public static String sha256Hex(String secret, String body) {
        return sha256Hex(secret, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算 HMAC-SHA256 hex
     */
    public static String sha256Hex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(body == null ? new byte[0] : body);
            return toHex(raw);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("hmac compute failed", e);
        }
    }

    /**
     * 常量时间字符串比较，避免时序侧信道攻击
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
