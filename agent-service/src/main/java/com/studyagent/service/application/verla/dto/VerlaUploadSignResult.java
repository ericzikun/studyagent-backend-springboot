package com.studyagent.service.application.verla.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上传签名响应（service → controller 映射 VO）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaUploadSignResult {

    private String objectId;
    /** 前端直传终点（相对路径，需拼接网关 host） */
    private String uploadPath;
    private String method;
    /** 上传时必须带的请求头：X-Verla-Upload-Token */
    private String uploadToken;
    /** OSS 对象 Key（Java 在 sign 时生成）；供 Python 直传 OSS 时定位目标对象 */
    private String ossKey;
    private long expiresInSeconds;
}
