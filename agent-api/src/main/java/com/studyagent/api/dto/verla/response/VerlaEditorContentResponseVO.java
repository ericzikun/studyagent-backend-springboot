package com.studyagent.api.dto.verla.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Conversation 维度编辑器内容响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaEditorContentResponseVO {

    private Long conversationId;
    private String artifactUid;
    private String kind;
    private Boolean exists;
    private Long editorContentId;
    private String title;
    private Map<String, Object> content;
    private Map<String, Object> meta;
    private Integer versionNo;
    private String sourceArtifactUid;
    private String seedArtifactUid;
    private Boolean parseError;
}
