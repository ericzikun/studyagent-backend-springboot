package com.studyagent.api.dto.verla.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SendMessageRequest {

    /**
     * 用户可见正文。轮次处于作业确认等待态时，应为 JSON：
     * {@code {"kind":"verla_assignment_confirm","skipped":true}} 或含 {@code answers} 等字段。
     */
    @NotBlank
    private String text;

    /** 附件列表（kind / url / filename） */
    private List<Map<String, Object>> attachments;

    /** 前端 UUID，幂等键（MVP 暂不强制幂等，预留） */
    private String clientMessageId;

    /** 默认 true，复用 conv.primaryIntent */
    private Boolean skipPlanIfPossible;
}
