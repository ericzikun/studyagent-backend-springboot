package com.studyagent.service.application.verla.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Verla V2 附件在 OSS 上的对象 Key（与 legacy {@code FileApplicationService} 使用的 Key 规则隔离）。
 */
public final class VerlaAttachmentOssKeys {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private VerlaAttachmentOssKeys() {
    }

    /**
     * @param prefix    配置项 {@code verla.attachment.oss-key-prefix}，已 trim，不含首尾 /
     * @param conversationId 必填，写入路径保证按会话隔离
     */
    public static String build(String prefix, long conversationId, String objectId, String filename) {
        String datePath = LocalDate.now(CN).format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String p = normalizePrefix(prefix);
        String safe = sanitizeFilename(filename);
        return String.format("%s%d/%s/%s_%s", p, conversationId, datePath, objectId, safe);
    }

    static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String p = prefix.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        return p.isEmpty() ? "" : p + "/";
    }

    static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "uploaded_file";
        }
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
