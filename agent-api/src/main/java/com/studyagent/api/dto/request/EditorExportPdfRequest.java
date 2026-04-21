package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EditorExportPdfRequest {

    @NotBlank(message = "taskId 不能为空")
    private String taskId;

    @NotBlank(message = "sourceObjectId 不能为空")
    private String sourceObjectId;

    private String filename;
}
