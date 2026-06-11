package com.studyagent.common.verla.id;

import org.apache.commons.lang3.StringUtils;
import org.sqids.Sqids;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * V2 对外 public id 编解码。
 * <p>
 * 内部仍使用 BIGINT 主键；API / URL / 埋点统一暴露带类型前缀的 Sqids 短码。
 * 迁移期支持纯数字 path（双读），便于旧链接兼容。
 */
public final class VerlaPublicIdCodec {

    private static final Sqids SQIDS = Sqids.builder()
            .alphabet(PublicIdAlphabet.SQIDS_ALPHABET)
            .minLength(PublicIdAlphabet.MIN_LENGTH)
            .build();

    private VerlaPublicIdCodec() {}

    public static String encode(VerlaPublicIdType type, Long internalId) {
        if (internalId == null) {
            return null;
        }
        return encode(type, internalId.longValue());
    }

    public static String encode(VerlaPublicIdType type, long internalId) {
        if (internalId <= 0) {
            throw new IllegalArgumentException("internal id must be positive: " + internalId);
        }
        String encoded = SQIDS.encode(Collections.singletonList(internalId));
        if (type == VerlaPublicIdType.LEGACY_TASK || !type.hasPrefix()) {
            return encoded;
        }
        return type.getPrefix() + "_" + encoded;
    }

    public static Optional<VerlaPublicId> tryDecode(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Optional.empty();
        }
        String trimmed = raw.trim();

        if (isPlainNumericId(trimmed)) {
            return Optional.empty();
        }

        VerlaPublicIdType legacyType = VerlaPublicIdType.LEGACY_TASK;
        String encodedPart = trimmed;
        int underscore = trimmed.indexOf('_');
        if (underscore > 0) {
            String prefix = trimmed.substring(0, underscore).toLowerCase(Locale.ROOT);
            VerlaPublicIdType matched = matchPrefix(prefix);
            if (matched == null) {
                return Optional.empty();
            }
            legacyType = matched;
            encodedPart = trimmed.substring(underscore + 1);
        }

        if (StringUtils.isBlank(encodedPart)) {
            return Optional.empty();
        }

        try {
            List<Long> numbers = SQIDS.decode(encodedPart);
            if (numbers == null || numbers.isEmpty()) {
                return Optional.empty();
            }
            long internalId = numbers.get(0);
            if (internalId <= 0) {
                return Optional.empty();
            }
            return Optional.of(new VerlaPublicId(legacyType, internalId));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * 解析 path / query 中的 public id，迁移期兼容纯数字。
     */
    public static long requireInternalId(VerlaPublicIdType expectedType, String raw) {
        if (StringUtils.isBlank(raw)) {
            throw new IllegalArgumentException("public id is blank");
        }
        String trimmed = raw.trim();

        if (isPlainNumericId(trimmed)) {
            long numeric = Long.parseLong(trimmed);
            if (numeric <= 0) {
                throw new IllegalArgumentException("invalid numeric public id: " + raw);
            }
            return numeric;
        }

        VerlaPublicId parsed = tryDecode(trimmed)
                .orElseThrow(() -> new IllegalArgumentException("invalid public id: " + raw));

        if (parsed.type().hasPrefix() && parsed.type() != expectedType) {
            throw new IllegalArgumentException(
                    "public id type mismatch, expected " + expectedType.getPrefix()
                            + " but got " + parsed.type().getPrefix());
        }
        return parsed.internalId();
    }

    /** V1 taskId：无前缀 Sqids 短码。 */
    public static Long decodeLegacyTaskId(String encoded) {
        if (StringUtils.isBlank(encoded)) {
            return null;
        }
        String trimmed = encoded.trim();
        if (isPlainNumericId(trimmed)) {
            return Long.parseLong(trimmed);
        }
        return decodeSqidsOnly(trimmed);
    }

    public static String encodeLegacyTaskId(Long taskId) {
        return encode(VerlaPublicIdType.LEGACY_TASK, taskId);
    }

    private static Long decodeSqidsOnly(String encoded) {
        try {
            List<Long> numbers = SQIDS.decode(encoded);
            return (numbers != null && !numbers.isEmpty()) ? numbers.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static VerlaPublicIdType matchPrefix(String prefix) {
        for (VerlaPublicIdType type : VerlaPublicIdType.values()) {
            if (type.hasPrefix() && type.getPrefix().equals(prefix)) {
                return type;
            }
        }
        return null;
    }

    private static boolean isPlainNumericId(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
