package com.studyagent.api.dto.response;

import com.studyagent.api.common.Meta;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件上传响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadFileResponse {
    private Meta meta;
    private String objectId;
}

