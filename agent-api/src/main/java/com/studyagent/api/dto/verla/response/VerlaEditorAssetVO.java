package com.studyagent.api.dto.verla.response;

import com.studyagent.service.domain.verla.VerlaEditorAsset;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaEditorAssetVO {

    private String assetId;
    private Long conversationId;
    private String artifactUid;
    private String editorKind;
    private String assetRole;
    private String filename;
    private String mime;
    private Long sizeBytes;
    private String status;
    private String publicUrl;
    private LocalDateTime createdAt;

    public static VerlaEditorAssetVO from(VerlaEditorAsset asset) {
        return VerlaEditorAssetVO.builder()
                .assetId(asset.getAssetId())
                .conversationId(asset.getConversationId())
                .artifactUid(asset.getArtifactUid())
                .editorKind(asset.getEditorKind())
                .assetRole(asset.getAssetRole())
                .filename(asset.getFilename())
                .mime(asset.getMime())
                .sizeBytes(asset.getSizeBytes())
                .status(asset.getStatus())
                .createdAt(asset.getCreatedAt())
                .build();
    }
}
