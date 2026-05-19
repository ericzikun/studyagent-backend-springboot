package com.studyagent.api.dto.verla.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 保存 conversation 维度编辑器内容请求。
 */
@Data
public class SaveVerlaEditorContentRequest {

    @NotNull(message = "content is required")
    private Map<String, Object> content;

    private String title;

    private Map<String, Object> meta;

    private String seedArtifactUid;

    private String saveSource;

    private Integer contentSchemaVersion;
}
