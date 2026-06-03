package com.studyagent.api.dto.verla.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaEditorAssetSignResponseVO {

    private String assetId;
    private String uploadPath;
    private String method;
    private Map<String, String> headers;
    private long expiresInSeconds;
}
