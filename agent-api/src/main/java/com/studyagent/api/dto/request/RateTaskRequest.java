package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

/**
 * 任务评价请求
 */
@Data
public class RateTaskRequest {
    @NotNull(message = "任务ID不能为空")
    private Long taskId;
    
    @NotNull(message = "评分不能为空")
    @DecimalMin(value = "0.0", message = "评分不能小于0")
    @DecimalMax(value = "5.0", message = "评分不能大于5")
    private Double score;
    
    private String content;
}

