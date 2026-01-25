package com.studyagent.api.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * 保存草稿响应
 */
@Data
@Builder
public class SaveDraftResponse {
    private Long draftId;
    private String savedAt;
}

