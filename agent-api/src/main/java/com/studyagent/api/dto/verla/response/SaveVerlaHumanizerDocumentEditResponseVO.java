package com.studyagent.api.dto.verla.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * humanizer 右栏编辑版本保存响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveVerlaHumanizerDocumentEditResponseVO {

    private String conversationId;
    private String artifactUid;
    private Boolean saved;

    /** 本次保存的编辑时间（updated_at）。 */
    private LocalDateTime updatedAt;
}
