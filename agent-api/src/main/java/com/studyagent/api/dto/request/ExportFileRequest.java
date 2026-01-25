package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 文件导出请求
 */
@Data
public class ExportFileRequest {
    @NotBlank(message = "文件对象ID不能为空")
    private String objectId;
}

