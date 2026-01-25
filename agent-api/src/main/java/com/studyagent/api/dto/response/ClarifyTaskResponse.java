package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 追问任务响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClarifyTaskResponse {
    private List<String> questions; // 追问问题列表
    private String suggestions; // 建议或提示信息
}

