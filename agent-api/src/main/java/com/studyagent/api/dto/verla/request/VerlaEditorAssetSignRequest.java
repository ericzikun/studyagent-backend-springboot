package com.studyagent.api.dto.verla.request;

import com.studyagent.api.jackson.verla.VerlaPublicIdField;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaEditorAssetSignRequest {

    @VerlaPublicIdField(VerlaPublicIdType.CONVERSATION)
    private Long conversationId;
    private String artifactUid;
    private String filename;
    private String mime;
    private Long sizeBytes;
    private String editorKind;
    private String assetRole;
    private String metaJson;
}
