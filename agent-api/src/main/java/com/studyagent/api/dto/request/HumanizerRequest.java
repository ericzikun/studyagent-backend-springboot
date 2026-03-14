package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Humanizer 通用请求 DTO
 */
@Data
public class HumanizerRequest {
    @NotBlank(message = "text 不能为空")
    @Size(max = 60000, message = "文本长度不能超过 10000 词（约 60000 字符）")
    private String text;

    /** 任务来源: HUMANIZER_PAGE / EDITOR，可选 */
    private String source;
}
