package com.studyagent.api.dto.verla.request;

import lombok.Data;

import java.util.Map;

@Data
public class CreateConversationRequest {

    /** 可选，不传时后端用 "新对话" */
    private String title;

    /** 可选，前端可预填知道的意图（如从入口埋点） */
    private String primaryIntent;

    /** 可选，全局偏好（lang / 工具开关 等） */
    private Map<String, Object> workspace;
}
