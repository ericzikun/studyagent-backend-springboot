package com.studyagent.api.dto.verla.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /v1/verla/v2/uploads/{objectId}/finalize
 * <p>
 * 上传凭证使用请求头 {@code X-Verla-Upload-Token}（与 sign 响应一致）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaUploadFinalizeRequest {

    private Long turnId;
    /** 可选：客户端计算的 SHA-256 hex，与落盘文件校验 */
    private String checksumSha256;
}
