package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 提交反馈响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitFeedbackResponse {

    private boolean success;
    private String submissionId;
}
