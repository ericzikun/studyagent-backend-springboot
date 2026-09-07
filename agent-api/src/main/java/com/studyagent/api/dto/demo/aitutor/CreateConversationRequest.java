package com.studyagent.api.dto.demo.aitutor;

import lombok.Data;

@Data
public class CreateConversationRequest {
    private String initialQuery;
    private String paperMeta; // JSON 字符串（可选）
}
