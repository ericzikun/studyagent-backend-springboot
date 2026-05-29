package com.studyagent.api.dto.verla.response;

import com.studyagent.service.domain.verla.VerlaAttachment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 附件元数据 VO（§16 / §17 / §31）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaAttachmentVO {

    private String objectId;
    private Long conversationId;
    private Long turnId;
    private Long sourceMessageId;
    /** file | image */
    private String kind;
    private String name;
    private String mimeType;
    private Long sizeBytes;
    /** 内部 URI / OSS URI（Py internal）；用户面默认不下发 */
    private String storageUri;
    /** OSS 对象 Key（internal 消费下载） */
    private String ossKey;
    private String extractStatus;
    private String extractedTextRef;
    private String summary;
    private String thumbnailUrl;
    private String attachmentOrigin;
    /** 文档编辑器图片场景下后端生成的 OSS 公网访问 URL */
    private String publicUrl;
    private LocalDateTime createdAt;

    public static VerlaAttachmentVO fromUser(VerlaAttachment a) {
        return from(a, false, false);
    }

    public static VerlaAttachmentVO fromInternal(VerlaAttachment a) {
        return from(a, true, true);
    }

    private static VerlaAttachmentVO from(VerlaAttachment a, boolean includeStorageUri, boolean includeOssKey) {
        if (a == null) {
            return null;
        }
        String mime = a.getMime();
        String kind = "file";
        if (mime != null && mime.toLowerCase().startsWith("image/")) {
            kind = "image";
        }
        return VerlaAttachmentVO.builder()
                .objectId(a.getObjectId())
                .conversationId(a.getConversationId())
                .turnId(a.getTurnId())
                .sourceMessageId(null)
                .kind(kind)
                .name(a.getFilename())
                .mimeType(mime)
                .sizeBytes(a.getSizeBytes())
                .storageUri(includeStorageUri ? a.getStorageUri() : null)
                .ossKey(includeOssKey ? a.getOssKey() : null)
                .extractStatus(a.getStatus())
                .extractedTextRef(a.getPrimaryArtifactUid())
                .summary(a.getSummary())
                .thumbnailUrl(null)
                .attachmentOrigin(a.getAttachmentOrigin())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
