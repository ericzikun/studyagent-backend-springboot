package com.studyagent.api.dto.verla.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpsertEditorPreviewRequest {

    @NotBlank
    private String previewUrl;

    private String attachmentObjectId;
    private String contentHash;
    private String captureSource;
    private Integer width;
    private Integer height;
}
