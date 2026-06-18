package com.studyagent.api.dto.verla.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 上传签名响应（§5）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaUploadSignResponseVO {

    private String objectId;
    /** 相对路径，前端拼接当前 API host */
    private String uploadPath;
    private String method;
    /** 上传时必须带的 Header（key → value） */
    private Map<String, String> headers;
    /** OSS 对象 Key（Java 生成）；供 Python 直传 OSS 时定位目标对象，前端无需使用 */
    private String ossKey;
    private long expiresInSeconds;
}
