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
    private Long taskId;
    private Boolean exists;
    private Long id;
    private String title;
    private Map<String, Object> content;
    private Boolean parseError;
}

