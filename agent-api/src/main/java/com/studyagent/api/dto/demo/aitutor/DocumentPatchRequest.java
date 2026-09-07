package com.studyagent.api.dto.demo.aitutor;

import lombok.Data;

@Data
public class DocumentPatchRequest {
    private String contentMd;
    private Long baseVersion;
}
