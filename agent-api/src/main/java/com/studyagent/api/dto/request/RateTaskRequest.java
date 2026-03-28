package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 任务评价请求
 * taskId 为 Sqids 编码后的对外 ID
 */
@Data
public class RateTaskRequest {
    @NotBlank(message = "任务ID不能为空")
    private String taskId;
    
    @NotNull(message = "评分不能为空")
    @DecimalMin(value = "0.0", message = "评分不能小于0")
    @DecimalMax(value = "5.0", message = "评分不能大于5")
    private Double score;
    
    private String content;
}

