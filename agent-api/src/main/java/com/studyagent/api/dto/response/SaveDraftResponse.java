package com.studyagent.api.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * 保存草稿响应
 */
@Data
@Builder
public class SaveDraftResponse {
    /** 对外暴露的 draftId（Sqids 编码） */
    private String draftId;
    private String savedAt;
}

