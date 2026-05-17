package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 保存编辑器内容请求
 */
@Data
public class SaveEditorContentRequest {
    @NotNull(message = "内容不能为空")
    private Map<String, Object> content;

    private String title;

    private Map<String, Object> meta;

    private String sourceArtifactUid;

    private String sourceObjectId;

    private String saveSource;

    private Integer contentSchemaVersion;
}
