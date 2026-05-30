package com.studyagent.api.dto.verla.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaEditorPreviewVO {

    private Long conversationId;
    private String artifactUid;
    private String kind;
    private String previewUrl;
    private String attachmentObjectId;
    private String contentHash;
    private String captureSource;
    private Integer width;
    private Integer height;
    private LocalDateTime updatedAt;

    public static VerlaEditorPreviewVO fromEntity(
            com.studyagent.infra.entity.verla.VerlaEditorPreviewEntity entity) {
        return VerlaEditorPreviewVO.builder()
                .conversationId(entity.getConversationId())
                .artifactUid(entity.getSourceArtifactUid())
                .kind(entity.getEditorKind())
                .previewUrl(entity.getPreviewUrl())
                .attachmentObjectId(entity.getAttachmentObjectId())
                .contentHash(entity.getContentHash())
                .captureSource(entity.getCaptureSource())
                .width(entity.getWidth())
                .height(entity.getHeight())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
