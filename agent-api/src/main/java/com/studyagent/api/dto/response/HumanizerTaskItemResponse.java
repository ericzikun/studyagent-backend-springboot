package com.studyagent.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务列表单条（精简，不含大字段）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HumanizerTaskItemResponse {

    private Long id;
    private String taskType;
    private String status;

    /** 输入文本前50字符预览 */
    private String inputTextPreview;

    /** Detect 结果 */
    private Double probability;
    private String label;
    private Integer totalSentences;
    private Integer completedSentences;

    /** Humanize 结果预览（前50字符） */
    private String resultTextPreview;

    private Double elapsedSeconds;
    private String errorMessage;
    private String createdAt;
}
