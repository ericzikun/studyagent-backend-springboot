package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EditorExportWordRequest {

    @NotBlank(message = "taskId 不能为空")
    private String taskId;

    @NotBlank(message = "文件内容不能为空")
    private String rawBody;

    private String filename;
}
