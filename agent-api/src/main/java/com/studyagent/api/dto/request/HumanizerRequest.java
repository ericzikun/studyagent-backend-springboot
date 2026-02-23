package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Humanizer 通用请求 DTO
 * 用于 AI 检测（detect-stream）和文本改写（process）两个端点
 */
@Data
public class HumanizerRequest {
    @NotBlank(message = "text 不能为空")
    private String text;
}
