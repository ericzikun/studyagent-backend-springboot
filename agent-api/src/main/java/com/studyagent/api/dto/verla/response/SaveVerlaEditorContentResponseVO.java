package com.studyagent.api.dto.verla.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 保存 conversation 维度编辑器内容响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveVerlaEditorContentResponseVO {

    private String conversationId;
    private String artifactUid;
    private String kind;
    private Long editorContentId;
    private String title;
    private Integer versionNo;
    private LocalDateTime updatedAt;
    private Boolean saved;
    private Boolean created;
}
