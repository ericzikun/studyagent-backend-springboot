package com.studyagent.api.dto.demo.aitutor;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ChatRequest {
    private String message;
    /** 首轮可携带论文设定：兼容对象或 JSON 字符串两种入参，与 CreateConversationRequest 同形 */
    private JsonNode paperMeta;
}
