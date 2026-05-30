package com.studyagent.api.dto.verla.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EditorPreviewItem {

    private String kind;
    private String previewUrl;
    private LocalDateTime updatedAt;
}
