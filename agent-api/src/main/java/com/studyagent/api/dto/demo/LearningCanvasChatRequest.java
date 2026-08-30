package com.studyagent.api.dto.demo;

import lombok.Data;

/**
 * Learning Canvas chat 请求（SSE 流式）
 */
@Data
public class LearningCanvasChatRequest {

    /** 用户消息（可为内部指令，如组件提交 / 节点深聊） */
    private String message;
}
