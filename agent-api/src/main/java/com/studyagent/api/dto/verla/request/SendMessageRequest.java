package com.studyagent.api.dto.verla.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SendMessageRequest {

    @NotBlank
    private String text;

    /** 附件列表（kind / url / filename） */
    private List<Map<String, Object>> attachments;

    /** 前端 UUID，幂等键（MVP 暂不强制幂等，预留） */
    private String clientMessageId;

    /** 默认 true，复用 conv.primaryIntent */
    private Boolean skipPlanIfPossible;

    /** 非空时跳过 Plan，直接走 AI 检测 / Humanizer（值为 AI_DETECTION 或 AI_HUMANIZER） */
    private String forceIntent;

    /** Agent 输出语言偏好（可选，如 "english" / "chinese" / "zh-CN"，见 OutputLanguage 枚举） */
    private String outputLanguage;
}
