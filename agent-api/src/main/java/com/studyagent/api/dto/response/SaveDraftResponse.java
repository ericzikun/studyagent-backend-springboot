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

    /**
     * 为 true 表示命中短时间内容幂等，未新建草稿（与上次成功保存的请求内容指纹相同）。
     */
    @Builder.Default
    private boolean deduplicated = false;
}

