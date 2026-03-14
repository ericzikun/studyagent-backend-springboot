package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 提交反馈请求
 */
@Data
public class SubmitFeedbackRequest {

    @NotBlank(message = "promptSessionId cannot be empty")
    private String promptSessionId;

    /** rating 型必填，1-5 */
    private Integer score;

    /** thumb 型必填，up / down */
    private String vote;

    @NotNull(message = "selectedTagCodes cannot be null")
    private List<String> selectedTagCodes;

    /** 允许空字符串 */
    private String comment;

    private String contact;
}
