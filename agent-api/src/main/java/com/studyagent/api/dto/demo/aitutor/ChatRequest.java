package com.studyagent.api.dto.demo.aitutor;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ChatRequest {
    private String message;
    /** 首轮可携带会话上下文（学科/年级/学习目标）：兼容对象或 JSON 字符串两种入参，与 CreateConversationRequest 同形 */
    private JsonNode sessionContext;
}
