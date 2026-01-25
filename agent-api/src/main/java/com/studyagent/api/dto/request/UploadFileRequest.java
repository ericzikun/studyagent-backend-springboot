package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 文件上传请求
 */
@Data
public class UploadFileRequest {
    @NotBlank(message = "文件内容不能为空")
    private String rawBody; // base64编码的文件内容
    
    private String filename; // 文件名（可选，如果不提供则使用默认值）
}

