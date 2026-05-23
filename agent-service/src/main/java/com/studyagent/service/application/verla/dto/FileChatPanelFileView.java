package com.studyagent.service.application.verla.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileChatPanelFileView {

    private String objectId;
    private String name;
    private String mimeType;
    private Long sizeBytes;
    private String extractStatus;
}
