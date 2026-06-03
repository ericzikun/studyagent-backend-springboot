package com.studyagent.service.application.verla.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaEditorAssetSignResult {

    private String assetId;
    private String uploadPath;
    private String method;
    private String uploadToken;
    private long expiresInSeconds;
}
