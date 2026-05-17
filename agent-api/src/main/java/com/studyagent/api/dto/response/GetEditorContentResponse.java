package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 获取编辑器内容响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GetEditorContentResponse {
    /** 对外暴露的 taskId（Sqids 编码） */
    private String taskId;
    private String kind;
    private Boolean exists;
    private Long id;
    private String title;
    private Map<String, Object> content;
    private Map<String, Object> meta;
    private Integer versionNo;
    private String sourceArtifactUid;
    private String sourceObjectId;
    private Boolean parseError;
}
