package com.studyagent.api.dto.response;

import com.studyagent.api.common.Meta;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件导出响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportFileResponse {
    private Meta meta;
    private String contentType;
    private String rawBody; // base64编码的文件内容
}

