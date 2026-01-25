package com.studyagent.api.dto.response;

import com.studyagent.api.common.Meta;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 提交任务响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitTaskResponse {
    private Meta meta;
    private Long taskId;
}

